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

package org.eclipse.jetty.http2;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HTTP2StreamEndPoint implements EndPoint, Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2StreamEndPoint.class);

    private final AtomicReference<WriteState> writeState = new AtomicReference<>(WriteState.IDLE);
    private final AtomicReference<Callback> readCallback = new AtomicReference<>();
    private final long created = System.currentTimeMillis();
    private final HTTP2Stream stream;
    private final AtomicBoolean eof = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<Content.Chunk> data = new AtomicReference<>();
    private Connection connection;

    public HTTP2StreamEndPoint(HTTP2Stream stream)
    {
        this.stream = stream;
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
                    if (!writeState.compareAndSet(current, WriteState.OSHUT))
                        break;
                    Callback oshutCallback = Callback.from(Invocable.InvocationType.NON_BLOCKING, this::oshutSuccess, this::oshutFailure);
                    stream.data(ReadableBuffer.EMPTY, true, oshutCallback);
                    return;
                case PENDING:
                    Callback callback = ((WriteState.Pending)current).callback;
                    if (!writeState.compareAndSet(current, new WriteState.Pending(WriteState.State.PENDING_OSHUT, this, callback)))
                        break;
                    return;
                case PENDING_OSHUT:
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
            case PENDING_OSHUT:
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
            if (current.state != WriteState.State.OSHUT)
                return;
            if (writeState.compareAndSet(current, new WriteState(WriteState.State.FAILED, failure)))
                return;
        }
    }

    @Override
    public boolean isOutputShutdown()
    {
        WriteState.State state = writeState.get().state;
        return state == WriteState.State.PENDING_OSHUT || state == WriteState.State.OSHUT || state == WriteState.State.FAILED;
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
                LOG.debug("closing {}", this, cause);
            Content.Chunk data = this.data.getAndSet(null);
            if (data != null)
                data.release();
            shutdownOutput();
            stream.close();
            connection.onClose(cause);
            onClose(cause);
        }
    }

    @Override
    public int fill(WritableBuffer sink) throws IOException
    {
        Content.Chunk data = this.data.get();
        if (data != null)
            return fillFromData(data, sink);

        Throwable failure = this.failure.get();
        if (failure != null)
            throw IO.rethrow(failure);

        if (eof.get())
            return -1;

        data = stream.read();
        this.data.set(data);

        if (LOG.isDebugEnabled())
            LOG.debug("filled {} on {}", data, this);

        if (data == null)
            return 0;

        return fillFromData(data, sink);
    }

    private int fillFromData(Content.Chunk chunk, WritableBuffer sink)
    {
        int length = 0;
        ByteBuffer buffer = chunk.getByteBuffer();
        boolean hasContent = buffer.remaining() > 0L;
        if (hasContent)
            length = Math.toIntExact(BufferUtil.put(buffer, sink));

        if (buffer.remaining() == 0L)
        {
            boolean endStream = chunk.isLast();
            eof.set(endStream);
            chunk.release();
            this.data.set(null);
            if (!endStream)
                stream.demand();
            if (!hasContent)
                length = endStream ? -1 : 0;
        }
        return length;
    }

    @Override
    public boolean flush(ReadableBuffer buffer) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("flushing {} on {}", buffer, this);
        if (buffer == null || buffer.remaining() == 0)
            return true;

        // Differently from other EndPoint implementations, where write() calls flush(),
        // in this implementation all the work is done in write(), and flush() is mostly
        // a no-operation.
        // This is because the flush() semantic is that it must not leave pending
        // operations if it cannot write the buffers; therefore we cannot call
        // stream.data() from flush() because if the stream is congested, the buffers
        // would not be fully written, we would return false from flush(), but
        // stream.data() would remain as a pending operation.

        WriteState current = writeState.get();
        return switch (current.state)
        {
            case IDLE, PENDING -> false;
            case PENDING_OSHUT, OSHUT -> throw new EofException("Output shutdown");
            case FAILED -> throw IO.rethrow(current.failure);
        };
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
        {
            if (data.get() != null)
                process();
            else
                stream.demand();
        }
        return result;
    }

    @Override
    public boolean isFillInterested()
    {
        return readCallback.get() != null;
    }

    @Override
    public void write(ReadableBuffer buffer, Callback callback) throws WritePendingException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("writing {} on {}", buffer, this);
        if (buffer == null || buffer.remaining() == 0L)
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
                        WriteState.Pending pending = new WriteState.Pending(WriteState.State.PENDING, this, callback);
                        if (!writeState.compareAndSet(current, pending))
                            continue;
                        // TODO: we really need a Stream primitive to write multiple frames.
                        stream.data(buffer, false, pending);
                    }
                    case PENDING -> callback.failed(new WritePendingException());
                    case PENDING_OSHUT, OSHUT -> callback.failed(new EofException("Output shutdown"));
                    case FAILED -> callback.failed(current.failure);
                    default -> callback.failed(new IllegalStateException("Unexpected state: " + current.state));
                }
                return;
            }
        }
    }

    @Override
    public Callback cancelWrite(Throwable cause)
    {
        while (true)
        {
            WriteState current = writeState.get();
            switch (current.state)
            {
                case IDLE ->
                {
                    if (writeState.compareAndSet(current, new WriteState(WriteState.State.FAILED, cause)))
                    {
                        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                        return null;
                    }
                }
                case PENDING, PENDING_OSHUT ->
                {
                    if (writeState.compareAndSet(current, new WriteState(WriteState.State.FAILED, cause)))
                    {
                        WriteState.Pending pending = (WriteState.Pending)current;
                        // Initiate a reset() which eventually will complete the pending callback.
                        try (Callback.Combination callbacks = new Callback.Combination(pending.callback, cause))
                        {
                            stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), callbacks.newCallback());
                            return callbacks.newCallback();
                        }
                    }
                }
                case FAILED ->
                {
                    ExceptionUtil.addSuppressedIfNotAssociated(current.failure, cause);
                    return null;
                }
                default ->
                {
                    if (writeState.compareAndSet(current, new WriteState(WriteState.State.FAILED, cause)))
                        return null;
                }
            }
        }
    }

    private void writeSuccess()
    {
        while (true)
        {
            WriteState current = writeState.get();
            switch (current.state)
            {
                case PENDING ->
                {
                    if (!writeState.compareAndSet(current, WriteState.IDLE))
                        continue;
                    ((WriteState.Pending)current).callback.succeeded();
                }
                case PENDING_OSHUT ->
                {
                    if (!writeState.compareAndSet(current, WriteState.OSHUT))
                        continue;
                    ((WriteState.Pending)current).callback.succeeded();
                    // Complete the shutdown of the output.
                    stream.data(ReadableBuffer.EMPTY, true, Callback.NOOP);
                }
            }
            return;
        }
    }

    private void writeFailure(Throwable failure)
    {
        while (true)
        {
            WriteState current = writeState.get();
            switch (current.state)
            {
                case PENDING, PENDING_OSHUT ->
                {
                    if (!writeState.compareAndSet(current, new WriteState(WriteState.State.FAILED, failure)))
                        continue;
                    ((WriteState.Pending)current).callback.failed(failure);
                }
            }
            return;
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
    public InvocationType getInvocationType()
    {
        Callback callback = readCallback.get();
        return callback == null ? InvocationType.NON_BLOCKING : callback.getInvocationType();
    }

    @Override
    public String toString()
    {
        // Do not call Stream.toString() because it stringifies the attachment,
        // which could be this instance, therefore causing a StackOverflowError.
        return String.format("%s@%x[%s@%x#%d][w=%s]", TypeUtil.toShortName(getClass()), hashCode(),
            TypeUtil.toShortName(stream.getClass()), stream.hashCode(), stream.getId(),
            writeState);
    }

    private static class WriteState
    {
        public static final WriteState IDLE = new WriteState(State.IDLE);
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
            IDLE, PENDING, PENDING_OSHUT, OSHUT, FAILED
        }

        private static class Pending extends WriteState implements Callback
        {
            private final HTTP2StreamEndPoint endpoint;
            private final Callback callback;

            private Pending(State state, HTTP2StreamEndPoint endPoint, Callback callback)
            {
                super(state);
                this.endpoint = endPoint;
                this.callback = callback;
            }

            @Override
            public void succeeded()
            {
                endpoint.writeSuccess();
            }

            @Override
            public void failed(Throwable x)
            {
                endpoint.writeFailure(x);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return callback.getInvocationType();
            }
        }
    }
}
