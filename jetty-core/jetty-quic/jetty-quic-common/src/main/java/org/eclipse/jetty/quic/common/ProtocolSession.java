//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.common;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Represents an <em>established</em> stateful connection with a remote peer for a specific QUIC connection.</p>
 * <p>{@link ProtocolSession} is created only when the connection is established, and it is protocol specific,
 * depending on the protocol negotiated during the connection establishment, or explicitly configured.</p>
 * <p>{@link ProtocolSession} creates and manages {@link StreamEndPoint}s, so that protocols on top of QUIC
 * can view QUIC streams as if they were an {@link EndPoint}.</p>
 */
public abstract class ProtocolSession extends ContainerLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(ProtocolSession.class);

    private final ConcurrentMap<Long, StreamEndPoint> endPoints = new ConcurrentHashMap<>();
    private final Executor executor;
    private final ByteBufferPool byteBufferPool;
    private final Session session;

    public ProtocolSession(Executor executor, ByteBufferPool byteBufferPool, Session session)
    {
        this.executor = executor;
        this.byteBufferPool = byteBufferPool;
        this.session = session;
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public Session getSession()
    {
        return session;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        onStart();
    }

    protected void onStart()
    {
    }

    @Override
    protected void doStop() throws Exception
    {
        onStop();
        super.doStop();
    }

    protected void onStop()
    {
    }

    private StreamEndPoint newStreamEndPoint(Stream stream)
    {
        return new StreamEndPoint(this, stream);
    }

    public StreamEndPoint getStreamEndPoint(long streamId)
    {
        return endPoints.get(streamId);
    }

    public StreamEndPoint getOrCreateStreamEndPoint(Stream stream, Consumer<StreamEndPoint> consumer)
    {
        boolean[] created = new boolean[1];
        StreamEndPoint endPoint = endPoints.computeIfAbsent(stream.getId(), id ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("creating endpoint for stream #{} for {}", id, this);
            StreamEndPoint result = newStreamEndPoint(stream);
            created[0] = true;
            return result;
        });

        // The consumer must be executed outside the Map.compute() above,
        // since it may take a long time and it may be re-entrant, causing the
        // creation of two StreamEndPoint objects for the same stream id.
        if (created[0])
            consumer.accept(endPoint);

        if (LOG.isDebugEnabled())
            LOG.debug("returning {} for {}", endPoint, this);
        return endPoint;
    }

    public boolean removeStreamEndPoint(StreamEndPoint endPoint)
    {
        boolean removed = endPoints.remove(endPoint.getStream().getId()) != null;
        if (LOG.isDebugEnabled())
            LOG.debug("removed {} {} from {}", removed, endPoint, this);
        return removed;
    }

    public Collection<StreamEndPoint> getStreamEndPoints()
    {
        return endPoints.values();
    }

    public void openStreamEndPoint(StreamEndPoint endPoint)
    {
        try
        {
            Connection connection = newConnection(endPoint);
            endPoint.setConnection(connection);
            endPoint.onOpen();
            connection.onOpen();
        }
        catch (RuntimeException | Error x)
        {
            throw x;
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
    }

    protected void closeStreamEndPoint(StreamEndPoint endPoint, Throwable failure)
    {
        Connection connection = endPoint.getConnection();
        if (connection != null)
            connection.close(/*failure*/);
        else
            endPoint.close(failure);
    }

    protected abstract Connection newConnection(StreamEndPoint endPoint) throws IOException;

    public CompletableFuture<Void> shutdown()
    {
        return disconnect(new ConnectionCloseFrame(ErrorCode.NO_ERROR.code(), "shutdown"), null);
    }

    public boolean onIdleTimeout(TimeoutException timeout)
    {
        return true;
    }

    /**
     * <p>Performs an inward close upon sending a {@code CONNECTION_CLOSE} frame.</p>
     * <p>This method closes all the {@link Connection}s associated with the
     * {@link StreamEndPoint}s managed by this class.
     * In turn, the {@link Connection} typically closes its associated
     * {@link StreamEndPoint}, causing it to be removed from this class.
     * Lastly, it calls {@link #disconnect(ConnectionCloseFrame, Throwable)}.</p>
     *
     * @param frame the frame carrying the error code and reason
     * @return a {@link CompletableFuture} that completes when the frame send completes
     * @see Session#close(ConnectionCloseFrame)
     */
    public CompletableFuture<Void> close(ConnectionCloseFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session closed locally {} {}", frame, this);
        return closeAndDisconnect(frame, null);
    }

    /**
     * <p>Performs an inward close upon receiving a {@code CONNECTION_CLOSE} frame.</p>
     * <p>The behavior is identical to {@link #close(ConnectionCloseFrame)}.</p>
     *
     * @param frame the frame carrying the error code and reason
     */
    public void onClose(ConnectionCloseFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session closed remotely {} {}", frame, this);
        closeAndDisconnect(frame, null);
    }

    private CompletableFuture<Void> closeAndDisconnect(ConnectionCloseFrame frame, Throwable failure)
    {
        // Perform the close inwards, by closing the
        // Connection associated to the StreamEndPoint.
        for (StreamEndPoint streamEndPoint : getStreamEndPoints())
        {
            closeStreamEndPoint(streamEndPoint, failure);
        }

        // Start propagating outwards.
        return disconnect(frame, failure);
    }

    /**
     * <p>Performs an outward disconnection.</p>
     *
     * @param frame the frame carrying the error code and reason
     * @param failure the failure that caused the disconnect, or {@code null}
     * @return a {@link CompletableFuture} that completes when the frame send completes
     * @see Session#disconnect(ConnectionCloseFrame, Throwable)
     */
    public CompletableFuture<Void> disconnect(ConnectionCloseFrame frame, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting with {} on {}", frame, this, failure);
        // Terminate all the StreamEndPoints.
        // This clears the endPoints map of this class.
        for (StreamEndPoint streamEndPoint : getStreamEndPoints())
        {
            // This is a session failure, there is no need to disconnect the StreamEndPoint's stream.
            streamEndPoint.disconnect(frame.getErrorCode(), failure, false);
        }
        // Continue the propagation outwards.
        return getSession().disconnect(frame, failure);
    }

    public void offerTask(Runnable task)
    {
        AbstractSession session = (AbstractSession)getSession();
        session.offerTask(task);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent, new DumpableCollection("streamEndPoints", getStreamEndPoints()));
    }

    /**
     * <p>A factory for protocol specific instances of {@link ProtocolSession}.</p>
     */
    public interface Factory
    {
        ProtocolSession newProtocolSession(Session session, Map<String, Object> context);
    }


//    protected void onFailure(long error, String reason, Throwable failure)
//    {
//    }
//
//    protected abstract void onClose(long error, String reason);
}
