//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.internal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HTTP2StreamEndPoint implements EndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2StreamEndPoint.class);

    private final AtomicReference<WriteState> writeState = new AtomicReference<>(WriteState.IDLE);
    private final AtomicReference<Callback> readCallback = new AtomicReference<>();
    private final long created = System.currentTimeMillis();
    private final HTTP2Stream stream;
    private final AtomicBoolean eof = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private Stream.Data data;
    private Connection connection;

    public HTTP2StreamEndPoint(HTTP2Stream stream)
    {
        this.stream = stream;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        SocketAddress local = getLocalSocketAddress();
        if (local instanceof InetSocketAddress)
            return (InetSocketAddress)local;
        return null;
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return stream.getSession().getLocalSocketAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        SocketAddress remote = getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress)
            return (InetSocketAddress)remote;
        return null;
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return stream.getSession().getRemoteSocketAddress();
    }

    @Override
    public boolean isOpen()
    {
        return !closed.get();
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return created;
    }

    @Override
    public void shutdownOutput()
    {
        while (true)
        {
            WriteState current = writeState.get();
            switch (current.state)
            {
                case IDLE:
                case OSHUTTING:
                    if (!writeState.compareAndSet(current, WriteState.OSHUT))
                        break;
                    stream.data(new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.from(this::oshutSuccess, this::oshutFailure));
                    return;
                case PENDING:
                    if (!writeState.compareAndSet(current, WriteState.OSHUTTING))
                        break;
                    return;
                case OSHUT:
                case FAILED:
                    return;
            }
        }
    }

    private void oshutSuccess()
    {
        WriteState current = writeState.get();
        switch (current.state)
        {
            case IDLE:
            case PENDING:
            case OSHUTTING:
                throw new IllegalStateException();
            case OSHUT:
            case FAILED:
                break;
        }
    }

    private void oshutFailure(Throwable failure)
    {
        while (true)
        {
            WriteState current = writeState.get();
            switch (current.state)
            {
                case IDLE:
                case PENDING:
                case OSHUTTING:
                    throw new IllegalStateException();
                case OSHUT:
                    if (!writeState.compareAndSet(current, new WriteState(WriteState.State.FAILED, failure)))
                        break;
                    return;
                case FAILED:
                    return;
            }
        }
    }

    @Override
    public boolean isOutputShutdown()
    {
        WriteState.State state = writeState.get().state;
        return state == WriteState.State.OSHUTTING || state == WriteState.State.OSHUT;
    }

    @Override
    public boolean isInputShutdown()
    {
        return eof.get();
    }

    @Override
    public void close(Throwable cause)
    {
        if (closed.compareAndSet(false, true))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("closing {}, cause: {}", this, cause);
            shutdownOutput();
            stream.close();
            onClose(cause);
        }
    }

    @Override
    public int fill(ByteBuffer sink) throws IOException
    {
        if (data != null)
            return fillFromData(sink);

        Throwable failure = this.failure.get();
        if (failure != null)
            throw IO.rethrow(failure);

        if (eof.get())
            return -1;

        Stream.Data data = this.data = stream.readData();

        if (LOG.isDebugEnabled())
            LOG.debug("filled {} on {}", data, this);

        if (data == null)
            return 0;

        return fillFromData(sink);
    }

    private int fillFromData(ByteBuffer sink)
    {
        int sinkPosition = BufferUtil.flipToFill(sink);
        ByteBuffer source = data.frame().getData();
        int sourceLength = source.remaining();
        int length = Math.min(sourceLength, sink.remaining());
        int sourceLimit = source.limit();
        source.limit(source.position() + length);
        sink.put(source);
        source.limit(sourceLimit);
        BufferUtil.flipToFlush(sink, sinkPosition);

        if (!source.hasRemaining())
        {
            eof.set(data.frame().isEndStream());
            data.release();
            data = null;
            stream.demand();
        }

        return length;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("flushing {} on {}", BufferUtil.toDetailString(buffers), this);
        if (buffers == null || buffers.length == 0)
        {
            return true;
        }
        else
        {
            while (true)
            {
                WriteState current = writeState.get();
                switch (current.state)
                {
                    case IDLE:
                        if (!writeState.compareAndSet(current, WriteState.PENDING))
                            break;
                        // We must copy the buffers because, differently from
                        // write(), the semantic of flush() is that it does not
                        // own them, but stream.data() needs to own them.
                        ByteBuffer buffer = coalesce(buffers, true);
                        Callback.Completable callback = new Callback.Completable(Invocable.InvocationType.NON_BLOCKING);
                        stream.data(new DataFrame(stream.getId(), buffer, false), callback);
                        callback.whenComplete((nothing, failure) ->
                        {
                            if (failure == null)
                                flushSuccess();
                            else
                                flushFailure(failure);
                        });
                        return callback.isDone();
                    case PENDING:
                        return false;
                    case OSHUTTING:
                    case OSHUT:
                        throw new EofException("Output shutdown");
                    case FAILED:
                        Throwable failure = current.failure;
                        if (failure instanceof IOException)
                            throw (IOException)failure;
                        throw new IOException(failure);
                }
            }
        }
    }

    private void flushSuccess()
    {
        while (true)
        {
            WriteState current = writeState.get();
            switch (current.state)
            {
                case IDLE:
                case OSHUT:
                    throw new IllegalStateException();
                case PENDING:
                    if (!writeState.compareAndSet(current, WriteState.IDLE))
                        break;
                    return;
                case OSHUTTING:
                    shutdownOutput();
                    return;
                case FAILED:
                    return;
            }
        }
    }

    private void flushFailure(Throwable failure)
    {
        while (true)
        {
            WriteState current = writeState.get();
            switch (current.state)
            {
                case IDLE:
                case OSHUT:
                    throw new IllegalStateException();
                case PENDING:
                    if (!writeState.compareAndSet(current, new WriteState(WriteState.State.FAILED, failure)))
                        break;
                    return;
                case OSHUTTING:
                    shutdownOutput();
                    return;
                case FAILED:
                    return;
            }
        }
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
    public void fillInterested(Callback callback) throws ReadPendingException
    {
        if (!tryFillInterested(callback))
            throw new ReadPendingException();
    }

    @Override
    public boolean tryFillInterested(Callback callback)
    {
        boolean result = readCallback.compareAndSet(null, callback);
        if (result)
            process();
        return result;
    }

    @Override
    public boolean isFillInterested()
    {
        return readCallback.get() != null;
    }

    @Override
    public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("writing {} on {}", BufferUtil.toDetailString(buffers), this);
        if (buffers == null || buffers.length == 0 || remaining(buffers) == 0)
        {
            callback.succeeded();
        }
        else
        {
            while (true)
            {
                WriteState current = writeState.get();
                switch (current.state)
                {
                    case IDLE ->
                    {
                        if (!writeState.compareAndSet(current, WriteState.PENDING))
                            continue;
                        // TODO: we really need a Stream primitive to write multiple frames.
                        ByteBuffer result = coalesce(buffers, false);
                        stream.data(new DataFrame(stream.getId(), result, false), Callback.from(() -> writeSuccess(callback), x -> writeFailure(x, callback)));
                    }
                    case PENDING -> callback.failed(new WritePendingException());
                    case OSHUTTING, OSHUT -> callback.failed(new EofException("Output shutdown"));
                    case FAILED -> callback.failed(current.failure);
                }
                return;
            }
        }
    }

    private void writeSuccess(Callback callback)
    {
        while (true)
        {
            WriteState current = writeState.get();
            switch (current.state)
            {
                case IDLE, OSHUT -> callback.failed(new IllegalStateException());
                case PENDING ->
                {
                    if (!writeState.compareAndSet(current, WriteState.IDLE))
                        continue;
                    callback.succeeded();
                }
                case OSHUTTING ->
                {
                    callback.succeeded();
                    shutdownOutput();
                }
                case FAILED -> callback.failed(current.failure);
            }
            return;
        }
    }

    private void writeFailure(Throwable failure, Callback callback)
    {
        while (true)
        {
            WriteState current = writeState.get();
            switch (current.state)
            {
                case IDLE:
                case OSHUT:
                    callback.failed(new IllegalStateException(failure));
                    return;
                case PENDING:
                case OSHUTTING:
                    if (!writeState.compareAndSet(current, new WriteState(WriteState.State.FAILED, failure)))
                        break;
                    callback.failed(failure);
                    return;
                case FAILED:
                    return;
            }
        }
    }

    private long remaining(ByteBuffer... buffers)
    {
        long total = 0;
        for (ByteBuffer buffer : buffers)
        {
            total += buffer.remaining();
        }
        return total;
    }

    private ByteBuffer coalesce(ByteBuffer[] buffers, boolean forceCopy)
    {
        if (buffers.length == 1 && !forceCopy)
            return buffers[0];
        long capacity = remaining(buffers);
        if (capacity > Integer.MAX_VALUE)
            throw new BufferOverflowException();
        ByteBuffer result = BufferUtil.allocateDirect((int)capacity);
        for (ByteBuffer buffer : buffers)
        {
            BufferUtil.append(result, buffer);
        }
        return result;
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
            LOG.debug("onOpen {}", this);
    }

    @Override
    public void onClose(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onClose {}", this);
    }

    @Override
    public void upgrade(Connection newConnection)
    {
        Connection oldConnection = getConnection();

        ByteBuffer buffer = null;
        if (oldConnection instanceof Connection.UpgradeFrom)
            buffer = ((Connection.UpgradeFrom)oldConnection).onUpgradeFrom();

        if (oldConnection != null)
            oldConnection.onClose(null);

        if (LOG.isDebugEnabled())
            LOG.debug("upgrading from {} to {} with data {} on {}", oldConnection, newConnection, BufferUtil.toDetailString(buffer), this);

        setConnection(newConnection);
        if (newConnection instanceof Connection.UpgradeTo && buffer != null)
            ((Connection.UpgradeTo)newConnection).onUpgradeTo(buffer);

        newConnection.onOpen();
    }

    protected void processDataAvailable()
    {
        process();
    }

    protected void processFailure(Throwable failure)
    {
        if (this.failure.compareAndSet(null, failure))
            process();
    }

    private void process()
    {
        Callback callback = readCallback.getAndSet(null);
        if (callback != null)
            callback.succeeded();
    }

    @Override
    public String toString()
    {
        // Do not call Stream.toString() because it stringifies the attachment,
        // which could be this instance, therefore causing a StackOverflowError.
        return String.format("%s@%x[%s@%x#%d][w=%s]", getClass().getSimpleName(), hashCode(),
            stream.getClass().getSimpleName(), stream.hashCode(), stream.getId(),
            writeState);
    }

    private static class WriteState
    {
        public static final WriteState IDLE = new WriteState(State.IDLE);
        public static final WriteState PENDING = new WriteState(State.PENDING);
        public static final WriteState OSHUTTING = new WriteState(State.OSHUTTING);
        public static final WriteState OSHUT = new WriteState(State.OSHUT);

        private final State state;
        private final Throwable failure;

        private WriteState(State state)
        {
            this(state, null);
        }

        private WriteState(State state, Throwable failure)
        {
            this.state = state;
            this.failure = failure;
        }

        @Override
        public String toString()
        {
            return state.toString();
        }

        private enum State
        {
            IDLE, PENDING, OSHUTTING, OSHUT, FAILED
        }
    }
}
