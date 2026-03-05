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
import java.io.UncheckedIOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>An {@link EndPoint} implementation on top of a QUIC stream.</p>
 * <p>The correspondent {@link Connection} associated to this StreamEndPoint
 * parses and generates the protocol specific bytes transported by QUIC.</p>
 */
public class StreamEndPoint implements EndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(StreamEndPoint.class);

    private final AutoLock lock = new AutoLock();
    private final long created = System.currentTimeMillis();
    private final AtomicReference<WriteState> writeState = new AtomicReference<>(WriteState.IDLE);
    private final AtomicReference<Throwable> writeFailure = new AtomicReference<>();
    private final ProtocolSession protocolSession;
    private final Stream stream;
    private Connection connection;
    private Content.Chunk chunk;
    private Callback fillInterest;

    public StreamEndPoint(ProtocolSession protocolSession, Stream stream)
    {
        this.protocolSession = protocolSession;
        this.stream = stream;
    }

    public ProtocolSession getProtocolSession()
    {
        return protocolSession;
    }

    public Stream getStream()
    {
        return stream;
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return stream.getSession().getLocalSocketAddress();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return stream.getSession().getRemoteSocketAddress();
    }

    @Override
    public boolean isOpen()
    {
        return !stream.isClosed();
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return created;
    }

    @Override
    public Object getTransport()
    {
        return stream;
    }

    @Override
    public long getIdleTimeout()
    {
        return stream.getIdleTimeout();
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        stream.setIdleTimeout(idleTimeout);
    }

    @Override
    public void shutdownOutput()
    {
        while (true)
        {
            WriteState current = writeState.get();
            switch (current)
            {
                case IDLE:
                case CLOSING:
                    if (!writeState.compareAndSet(current, WriteState.CLOSED))
                        break;
                    shutdownOutput(ErrorCode.NO_ERROR.code(), Promise.Invocable.noop());
                    return;
                case PENDING:
                    if (!writeState.compareAndSet(current, WriteState.CLOSING))
                        break;
                    return;
                case CLOSED:
                case FAILED:
                    return;
            }
        }
    }

    @Override
    public boolean isOutputShutdown()
    {
        WriteState state = writeState.get();
        return state != WriteState.IDLE && state != WriteState.PENDING;
    }

    @Override
    public boolean isInputShutdown()
    {
        try (AutoLock ignored = lock.lock())
        {
            return chunk != null && chunk.isLast() && !chunk.hasRemaining();
        }
    }

    public void shutdownInput(long appError)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("shutting down input with error 0x{} on {}", Long.toHexString(appError), this);
        stream.stopSending(appError, Promise.Invocable.noop());
    }

    public void shutdownOutput(long appError, Promise.Invocable<StreamEndPoint> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("shutting down output with error 0x{} on {}", Long.toHexString(appError), this);
        stream.reset(appError, Promise.Invocable.toPromise(promise, stream -> this));
    }

    @Override
    public void close(Throwable failure)
    {
        // Implemented from EndPoint, must have blocking semantic.
        try (Blocker.Promise<StreamEndPoint> promise = Blocker.promise())
        {
            disconnect(ErrorCode.NO_ERROR.code(), failure, true, Promise.Invocable.from(() -> onClose(failure), promise));
            promise.block();
        }
        catch (IOException x)
        {
            throw new UncheckedIOException(x);
        }
    }

    public void disconnect(long appError, Throwable failure, boolean disconnectStream, Promise.Invocable<StreamEndPoint> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting with error 0x{} disconnectStream={} {} {}", Long.toHexString(appError), disconnectStream, this, String.valueOf(failure));

        // TODO: drain queue, store failure, and update write state.

        getProtocolSession().removeStreamEndPoint(this);

        if (!disconnectStream)
        {
            promise.succeeded(this);
            return;
        }

        // Propagate outwards.
        stream.disconnect(appError, failure, Promise.Invocable.toPromise(promise, s -> this));
    }

    @Override
    public void onClose(Throwable failure)
    {
        getConnection().onClose(failure);
    }

    void onIdleTimeout(TimeoutException timeout, Promise.Invocable<Boolean> promise)
    {
        promise.succeeded(true);
    }

    void onFailure(Throwable failure)
    {
        Callback callback;
        try (AutoLock ignored = lock.lock())
        {
            if (chunk == null)
            {
                chunk = Content.Chunk.from(failure);
            }
            else
            {
                // Keep EOF or existing failure, otherwise release and replace.
                if (!chunk.isLast() || chunk.hasRemaining())
                {
                    chunk.release();
                    chunk = Content.Chunk.from(failure);
                }
            }

            callback = fillInterest;
            fillInterest = null;
        }
        if (callback != null)
            callback.failed(failure);
        else
            protocolSession.onStreamFailure(stream.getId(), failure);
    }

    @Override
    public int fill(ByteBuffer sink) throws IOException
    {
        Content.Chunk current;
        try (AutoLock ignored = lock.lock())
        {
            current = chunk;
            // Keep EOF or failure, otherwise null out to allow for concurrent failures.
            if (current == null || !current.isLast() || current.hasRemaining())
                chunk = null;
        }

        while (true)
        {
            if (current != null)
            {
                ByteBuffer source = current.getByteBuffer();
                if (source.hasRemaining())
                {
                    int filled = copy(current, sink);

                    boolean release = true;
                    if (source.hasRemaining())
                    {
                        try (AutoLock ignored = lock.lock())
                        {
                            // Do not overwrite concurrent failures.
                            if (chunk == null)
                            {
                                chunk = current;
                                release = false;
                            }
                        }
                    }
                    else
                    {
                        try (AutoLock ignored = lock.lock())
                        {
                            // Do not overwrite concurrent failures.
                            if (chunk == null && current.isLast())
                                chunk = Content.Chunk.EOF;
                        }
                    }

                    if (release)
                        current.release();

                    if (LOG.isDebugEnabled())
                        LOG.debug("filled {} bytes on {}", filled, this);

                    return filled;
                }

                if (Content.Chunk.isFailure(current))
                {
                    try (AutoLock ignored = lock.lock())
                    {
                        // Do not overwrite concurrent failures.
                        if (chunk == null)
                            chunk = current;
                    }
                    throw IO.rethrow(current.getFailure());
                }

                if (current.isLast())
                {
                    if (current != Content.Chunk.EOF)
                    {
                        try (AutoLock ignored = lock.lock())
                        {
                            // Do not overwrite concurrent failures.
                            if (chunk == null)
                                chunk = Content.Chunk.EOF;
                        }
                        current.release();
                    }
                    return -1;
                }
            }

            current = stream.read();

            if (LOG.isDebugEnabled())
                LOG.debug("read {} from {} on {}", current, stream, this);

            if (current == null)
                return 0;
        }
    }

    /**
     * <p>Fills from this {@code StreamEndPoint}.</p>
     * <p>This method should be used in alternative to {@link #fill(ByteBuffer)},
     * when the {@link Connection} installed on top of this {@code StreamEndPoint}
     * needs to know both the bytes and whether the QUIC data is the last in the
     * stream, for example in HTTP/3.</p>
     * <p>The code calling this method is responsible to organize for the returned
     * {@link Content.Chunk} to be eventually {@link Content.Chunk#release() released}.</p>
     *
     * @return a {@link Content.Chunk} with data bytes or a failure, or {@code null}
     * if there are no data bytes
     */
    public Content.Chunk fill()
    {
        Content.Chunk current;
        try (AutoLock ignored = lock.lock())
        {
            current = chunk;
        }

        if (current == null)
            current = stream.read();

        return current;
    }

    private int copy(Content.Chunk chunk, ByteBuffer sink)
    {
        int length = 0;
        ByteBuffer source = chunk.getByteBuffer();
        if (source.hasRemaining())
        {
            int sinkPosition = BufferUtil.flipToFill(sink);
            int sourceLength = source.remaining();
            length = Math.min(sourceLength, sink.remaining());
            int sourceLimit = source.limit();
            source.limit(source.position() + length);
            sink.put(source);
            source.limit(sourceLimit);
            BufferUtil.flipToFlush(sink, sinkPosition);
        }
        return length;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("flushing {} on {}", BufferUtil.toDetailString(buffers), this);
        if (buffers == null || buffers.length == 0 || BufferUtil.remaining(buffers) == 0)
            return true;

        // Differently from other EndPoint implementations, where write() calls
        // flush(), in this implementation all the work is done in write(), and
        // flush() is mostly a no-operation.
        // This is because the flush() semantic is that it must not leave pending
        // operations if it cannot write the buffers; therefore we cannot call
        // stream.data() from flush() because if the stream is congested, the
        // buffers would not be fully written, we would return false from flush(),
        // but stream.data() would remain as a pending operation and possibly
        // operate on the buffers concurrently.

        return switch (writeState.get())
        {
            case IDLE, PENDING -> false;
            case CLOSING, CLOSED -> throw new EofException("output shutdown");
            case FAILED -> throw IO.rethrow(writeFailure.get());
        };
    }

    @Override
    public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
    {
        write(false, List.of(buffers), callback);
    }

    @Override
    public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
    {
        write(last, List.of(byteBuffer), callback);
    }

    @Override
    public Callback cancelWrite(Throwable cause)
    {
        // TODO
        throw new UnsupportedOperationException();
    }

    public void write(boolean last, List<ByteBuffer> buffers, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("writing last={} {} on {}", last, BufferUtil.toDetailString(buffers.toArray(ByteBuffer[]::new)), this);
        if (last || remaining(buffers) > 0)
        {
            while (true)
            {
                WriteState current = writeState.get();
                switch (current)
                {
                    case IDLE ->
                    {
                        if (!writeState.compareAndSet(current, WriteState.PENDING))
                            continue;
                        stream.data(last, buffers, new Promise.Invocable.Abstract<>(callback.getInvocationType())
                        {
                            @Override
                            public void succeeded(Stream result)
                            {
                                writeSuccess(callback);
                            }

                            @Override
                            public void failed(Throwable x)
                            {
                                writeFailure(x, callback);
                            }
                        });
                    }
                    case PENDING -> callback.failed(new WritePendingException());
                    case CLOSING, CLOSED -> callback.failed(new EofException("output shutdown"));
                    case FAILED -> callback.failed(writeFailure.get());
                    default -> callback.failed(new IllegalStateException("unexpected state: " + current));
                }
                return;
            }
        }
        else
        {
            callback.succeeded();
        }
    }

    private long remaining(List<ByteBuffer> buffers)
    {
        long remaining = 0;
        for (ByteBuffer buffer : buffers)
        {
            remaining += buffer.remaining();
        }
        return remaining;
    }

    private void writeSuccess(Callback callback)
    {
        while (true)
        {
            WriteState current = writeState.get();
            switch (current)
            {
                case PENDING ->
                {
                    if (!writeState.compareAndSet(current, WriteState.IDLE))
                        continue;
                    callback.succeeded();
                }
                case CLOSING ->
                {
                    // TODO: no state change?
                    callback.succeeded();
                    shutdownOutput();
                }
                case FAILED -> callback.failed(writeFailure.get());
                default -> callback.failed(new IllegalStateException("unexpected state: " + current));
            }
            return;
        }
    }

    private void writeFailure(Throwable failure, Callback callback)
    {
        while (true)
        {
            WriteState current = writeState.get();
            switch (current)
            {
                case PENDING, CLOSING ->
                {
                    writeFailure.compareAndSet(null, failure);
                    if (!writeState.compareAndSet(current, WriteState.FAILED))
                        continue;
                    callback.failed(failure);
                }
                case FAILED ->
                {
                    // Already failed.
                }
                default -> callback.failed(new IllegalStateException("unexpected state: " + current));
            }
            return;
        }
    }

    @Override
    public boolean isFillInterested()
    {
        try (AutoLock ignored = lock.lock())
        {
            return fillInterest != null;
        }
    }

    @Override
    public void fillInterested(Callback callback)
    {
        if (!tryFillInterested(callback))
            throw new ReadPendingException();
    }

    @Override
    public boolean tryFillInterested(Callback callback)
    {
        boolean set;
        try (AutoLock ignored = lock.lock())
        {
            set = fillInterest == null;
            if (set)
                fillInterest = callback;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("setting ({}) fill interest with {} on {}", set, callback, this);
        if (set)
            stream.demand();
        return set;
    }

    void fillable()
    {
        Callback callback;
        try (AutoLock ignored = lock.lock())
        {
            callback = fillInterest;
            fillInterest = null;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("notifying fillable via {} on {}", callback, this);
        if (callback != null)
        {
            // QUIC streams are serialized by succeeding the callback.
            // For only-1-stream-per-connection protocols such as HTTP/1
            // and HTTP/2, this is equivalent to TCP.
            // For multiple-streams-per-connection protocols such as HTTP/3,
            // this simplifies the handling of HTTP/3 frames from the control
            // stream and from message streams, because they are serialized and
            // not executed concurrently, which would require careful locking.
            callback.succeeded();
        }
    }

    @Override
    public Connection getConnection()
    {
        return connection;
    }

    @Override
    public void setConnection(Connection connection)
    {
        this.connection = connection;
    }

    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("opened {}", this);
    }

    @Override
    public void upgrade(Connection newConnection)
    {
        Connection oldConnection = getConnection();

        ByteBuffer byteBuffer = null;
        if (oldConnection instanceof Connection.UpgradeFrom from)
            byteBuffer = from.onUpgradeFrom();

        oldConnection.onClose(null);
        setConnection(newConnection);

        if (LOG.isDebugEnabled())
            LOG.debug("{} upgrading from {} to {} with {}",
                this, oldConnection, newConnection, BufferUtil.toDetailString(byteBuffer));

        if (BufferUtil.hasContent(byteBuffer))
        {
            if (newConnection instanceof Connection.UpgradeTo to)
                to.onUpgradeTo(byteBuffer);
            else
                throw new IllegalStateException("Cannot upgrade: " + newConnection + " does not implement " + Connection.UpgradeTo.class.getName());
        }

        newConnection.onOpen();
    }

    @Override
    public SslSessionData getSslSessionData()
    {
        AbstractSession session = (AbstractSession)stream.getSession();
        X509Certificate[] peerCertificates = session.getPeerCertificates();
        return SslSessionData.from(null, null, null, peerCertificates);
    }

    private String toConnectionString()
    {
        Connection connection = getConnection();
        if (connection == null)
            return "<null>";
        if (connection instanceof AbstractConnection c)
            return c.toConnectionString();
        return "%s@%x".formatted(TypeUtil.toShortName(connection.getClass()), connection.hashCode());
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x#%d[d=%s,%s]", TypeUtil.toShortName(getClass()), hashCode(), getStream().getId(), chunk, toConnectionString());
    }

    private enum WriteState
    {
        IDLE, PENDING, CLOSING, CLOSED, FAILED
    }
}
