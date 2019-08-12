//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Invocable;

public abstract class HTTP2StreamEndPoint implements EndPoint
{
    private static final Logger LOG = Log.getLogger(HTTP2StreamEndPoint.class);

    private final Deque<Entry> dataQueue = new ArrayDeque<>();
    private final AtomicReference<WriteState> writeState = new AtomicReference<>(WriteState.IDLE);
    private final AtomicReference<Callback> readCallback = new AtomicReference<>();
    private final long created = System.currentTimeMillis();
    private final AtomicBoolean eof = new AtomicBoolean();
    private final IStream stream;
    private Connection connection;

    public HTTP2StreamEndPoint(IStream stream)
    {
        this.stream = stream;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return stream.getSession().getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return stream.getSession().getRemoteAddress();
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
        if (LOG.isDebugEnabled())
            LOG.debug("closing {}, cause: {}", this, cause);
        shutdownOutput();
        // TODO: do we need this code after the shutdownOutput() callback?
        stream.close();
        onClose(cause);
    }

    @Override
    public int fill(ByteBuffer sink)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("filling {} on {}", BufferUtil.toDetailString(sink), this);

        Entry entry;
        synchronized (this)
        {
            entry = dataQueue.poll();
        }
        if (entry == null)
            return 0;
        if (entry.eof)
            return shutdownInput();

        int sinkPosition = BufferUtil.flipToFill(sink);
        ByteBuffer source = entry.buffer;
        int sourceLength = source.remaining();
        int length = Math.min(sourceLength, sink.remaining());
        int sourceLimit = source.limit();
        source.limit(source.position() + length);
        sink.put(source);
        source.limit(sourceLimit);
        BufferUtil.flipToFlush(sink, sinkPosition);

        if (source.hasRemaining())
        {
            synchronized (this)
            {
                dataQueue.offerFirst(entry);
            }
        }
        else
        {
            entry.callback.succeeded();
        }
        return length;
    }

    private int shutdownInput()
    {
        eof.set(true);
        return -1;
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
                    case IDLE:
                        if (!writeState.compareAndSet(current, WriteState.PENDING))
                            break;
                        // TODO: we really need a Stream primitive to write multiple frames.
                        ByteBuffer result = coalesce(buffers, false);
                        stream.data(new DataFrame(stream.getId(), result, false), Callback.from(() -> writeSuccess(callback), x -> writeFailure(x, callback)));
                        return;
                    case PENDING:
                        callback.failed(new WritePendingException());
                        return;
                    case OSHUTTING:
                    case OSHUT:
                        callback.failed(new EofException("Output shutdown"));
                        return;
                    case FAILED:
                        callback.failed(current.failure);
                        return;
                }
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
                case IDLE:
                case OSHUT:
                    callback.failed(new IllegalStateException());
                    return;
                case PENDING:
                    if (!writeState.compareAndSet(current, WriteState.IDLE))
                        break;
                    callback.succeeded();
                    return;
                case OSHUTTING:
                    callback.succeeded();
                    shutdownOutput();
                    return;
                case FAILED:
                    callback.failed(current.failure);
                    return;
            }
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
            total += buffer.remaining();
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
            BufferUtil.append(result, buffer);
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
    public boolean isOptimizedForDirectBuffers()
    {
        return true;
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

    protected void offerData(DataFrame frame, Callback callback)
    {
        ByteBuffer buffer = frame.getData();
        if (LOG.isDebugEnabled())
            LOG.debug("offering {} on {}", frame, this);
        if (frame.isEndStream())
        {
            if (buffer.hasRemaining())
                offer(buffer, Callback.from(() -> {}, callback::failed), false);
            offer(BufferUtil.EMPTY_BUFFER, callback, true);
        }
        else
        {
            if (buffer.hasRemaining())
                offer(buffer, callback, false);
            else
                callback.succeeded();
        }
        process();
    }

    private void offer(ByteBuffer buffer, Callback callback, boolean eof)
    {
        synchronized (this)
        {
            dataQueue.offer(new Entry(buffer, callback, eof));
        }
    }

    protected void process()
    {
        boolean empty;
        synchronized (this)
        {
            empty = dataQueue.isEmpty();
        }
        if (!empty)
        {
            Callback callback = readCallback.getAndSet(null);
            if (callback != null)
                callback.succeeded();
        }
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

    private static class Entry
    {
        private final ByteBuffer buffer;
        private final Callback callback;
        private final boolean eof;

        private Entry(ByteBuffer buffer, Callback callback, boolean eof)
        {
            this.buffer = buffer;
            this.callback = callback;
            this.eof = eof;
        }
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
