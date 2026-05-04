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
import org.eclipse.jetty.util.Promise;
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

    public StreamEndPoint createStreamEndPoint(Stream stream, Consumer<StreamEndPoint> initializer)
    {
        long streamId = stream.getId();
        StreamEndPoint streamEndPoint = endPoints.compute(streamId, (k, v) ->
        {
            if (v != null)
                throw new IllegalStateException("duplicate stream " + streamId);
            if (LOG.isDebugEnabled())
                LOG.debug("creating endpoint for stream #{} for {}", streamId, this);
            return newStreamEndPoint(stream);
        });
        initializer.accept(streamEndPoint);
        return streamEndPoint;
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

    private void closeStreamEndPoint(StreamEndPoint endPoint, Throwable failure)
    {
        Connection connection = endPoint.getConnection();
        if (connection != null)
            connection.close(/*failure*/);
        else
            endPoint.close(failure);
    }

    protected abstract Connection newConnection(StreamEndPoint endPoint) throws IOException;

    public CompletableFuture<ProtocolSession> shutdown()
    {
        CompletableFuture<ProtocolSession> completable = new CompletableFuture<>();
        disconnect(new ConnectionCloseFrame(ErrorCode.NO_ERROR.code(), "shutdown"), null, Promise.Invocable.toPromise(completable));
        return completable;
    }

    public boolean onIdleTimeout(TimeoutException timeout)
    {
        return true;
    }

    public void onStreamFailure(long streamId, Throwable failure)
    {
        StreamEndPoint streamEndPoint = getStreamEndPoint(streamId);
        if (streamEndPoint != null)
            streamEndPoint.disconnect(ErrorCode.NO_ERROR.code(), failure, true, Promise.Invocable.noop());
    }

    /**
     * <p>Performs an upward close upon sending a {@code CONNECTION_CLOSE} frame.</p>
     * <p>This method closes all the {@link Connection}s associated with the
     * {@link StreamEndPoint}s managed by this class.
     * In turn, the {@link Connection} typically closes its associated
     * {@link StreamEndPoint}, causing it to be removed from this class.
     * Lastly, it calls {@link #disconnect(ConnectionCloseFrame, Throwable, Promise.Invocable)}.</p>
     *
     * @param frame the frame carrying the error code and reason
     * @param promise a {@link Promise} that completes when the frame send completes
     * @see Session#close(ConnectionCloseFrame, Promise.Invocable)
     */
    public void close(ConnectionCloseFrame frame, Promise.Invocable<ProtocolSession> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session closed locally {} {}", frame, this);
        closeAndDisconnect(frame, promise);
    }

    /**
     * <p>Performs an upward close upon receiving a {@code CONNECTION_CLOSE} frame.</p>
     * <p>The behavior is identical to {@link #close(ConnectionCloseFrame, Promise.Invocable)}.</p>
     *
     * @param frame the frame carrying the error code and reason
     */
    public void onClose(ConnectionCloseFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session closed remotely {} {}", frame, this);
        closeAndDisconnect(frame, Promise.Invocable.noop());
    }

    private void closeAndDisconnect(ConnectionCloseFrame frame, Promise.Invocable<ProtocolSession> promise)
    {
        // Perform the close upwards, by closing the
        // Connection associated to the StreamEndPoint.
        for (StreamEndPoint streamEndPoint : getStreamEndPoints())
        {
            closeStreamEndPoint(streamEndPoint, null);
        }

        // Start propagating downwards.
        disconnect(frame, null, promise);
    }

    /**
     * <p>Performs a downward disconnection.</p>
     *
     * @param frame the frame carrying the error code and reason
     * @param failure the failure that caused the disconnect, or {@code null}
     * @param promise the {@link Promise} that gets notified when the
     * disconnect is complete
     * @see Session#disconnect(ConnectionCloseFrame, Throwable, Promise.Invocable)
     */
    public void disconnect(ConnectionCloseFrame frame, Throwable failure, Promise.Invocable<ProtocolSession> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting with {} on {}", frame, this, failure);
        // Terminate all the StreamEndPoints.
        // This clears the endPoints map of this class.
        for (StreamEndPoint streamEndPoint : getStreamEndPoints())
        {
            // This is a session failure, there is no need to disconnect the StreamEndPoint's stream.
            streamEndPoint.disconnect(frame.errorCode(), failure, false, Promise.Invocable.noop());
        }
        // Continue the propagation downwards.
        getSession().disconnect(frame, failure, Promise.Invocable.toPromise(promise, s -> this));
    }

    /**
     * @param task The task to offer to the execution strategy.
     * @param dispatch {@code true} to dispatch the task, {@code false} to produce in the calling thread.
     * Callers from application threads should use {@code true}, otherwise they may be arbitrarily
     * delayed. Callers from I/O threads should use {@code false} to avoid thread hops.
     */
    public void offerTask(Runnable task, boolean dispatch)
    {
        if (task == null)
            return;
        AbstractSession session = (AbstractSession)getSession();
        session.offerTask(task, dispatch);
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
}
