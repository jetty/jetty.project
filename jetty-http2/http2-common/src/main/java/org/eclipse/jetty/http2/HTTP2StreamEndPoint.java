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
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HTTP2StreamEndPoint implements EndPoint, HTTP2Channel
{
    private static final Logger LOG = Log.getLogger(HTTP2StreamEndPoint.class);

    private final Deque<Entry> dataQueue = new ArrayDeque<>();
    private final AtomicReference<Callback> readCallback = new AtomicReference<>();
    private final IStream stream;
    private Connection connection;
    private boolean oshut;

    public HTTP2StreamEndPoint(IStream stream)
    {
        this.stream = stream;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return null;
    }

    @Override
    public boolean isOpen()
    {
        return !stream.isClosed();
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return 0;
    }

    @Override
    public void shutdownOutput()
    {
        oshut = true;
        // TODO: I think I will need an IteratingCallback to avoid WritePendingExceptions
        stream.data(new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
    }

    @Override
    public boolean isOutputShutdown()
    {
        return oshut;
    }

    @Override
    public boolean isInputShutdown()
    {
        return false;
    }

    @Override
    public void close(Throwable cause)
    {
        LOG.info("close");
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        Entry entry;
        synchronized (this)
        {
            entry = dataQueue.poll();
        }
        if (entry == null)
            return 0;
        int position = BufferUtil.flipToFill(buffer);
        int length = Math.min(entry.buffer.remaining(), buffer.remaining());
        int limit = entry.buffer.limit();
        entry.buffer.limit(entry.buffer.position() + length);
        buffer.put(entry.buffer);
        entry.buffer.limit(limit);
        BufferUtil.flipToFlush(buffer, position);
        if (LOG.isDebugEnabled())
            LOG.debug("reading {} on {}", BufferUtil.toDetailString(buffer), this);
        if (entry.buffer.hasRemaining())
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
            // TODO: once more we need a queue to avoid WritePendingException.
            //  See shutdownOutput().
            ByteBuffer buffer = coalesce(buffers, true);
            stream.data(new DataFrame(stream.getId(), buffer, false), Callback.NOOP);
            return true;
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
        if (buffers == null || buffers.length == 0)
        {
            callback.succeeded();
        }
        else
        {
            // TODO: we really need a Stream primitive to write multiple frames.
            ByteBuffer result = coalesce(buffers, false);
            stream.data(new DataFrame(stream.getId(), result, false), callback);
        }
    }

    private ByteBuffer coalesce(ByteBuffer[] buffers, boolean forceCopy)
    {
        if (buffers.length == 1 && !forceCopy)
            return buffers[0];
        int capacity = Arrays.stream(buffers).mapToInt(Buffer::remaining).sum();
        ByteBuffer result = BufferUtil.allocateDirect(capacity);
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
        LOG.info("onOpen");
    }

    @Override
    public void onClose(Throwable cause)
    {
        LOG.info("onClose");
    }

    @Override
    public boolean isOptimizedForDirectBuffers()
    {
        return false;
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

    @Override
    public Runnable onData(DataFrame frame, Callback callback)
    {
        ByteBuffer buffer = frame.getData();
        if (LOG.isDebugEnabled())
            LOG.debug("offering {} on {}", BufferUtil.toDetailString(buffer), this);
        Entry entry = new Entry(buffer, callback);
        synchronized (this)
        {
            dataQueue.offer(entry);
        }
        process();
        return null;
    }

    @Override
    public Runnable onTrailer(HeadersFrame frame)
    {
        return null;
    }

    @Override
    public boolean onTimeout(Throwable failure, Consumer<Runnable> consumer)
    {
        return false;
    }

    @Override
    public Runnable onFailure(Throwable failure, Callback callback)
    {
        return null;
    }

    @Override
    public boolean isIdle()
    {
        return false;
    }

    private void process()
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
        return String.format("%s@%x[%s@%x#%d]", getClass().getSimpleName(), hashCode(),
                stream.getClass().getSimpleName(), stream.hashCode(), stream.getId());
    }

    private static class Entry
    {
        private final ByteBuffer buffer;
        private final Callback callback;

        private Entry(ByteBuffer buffer, Callback callback)
        {
            this.buffer = buffer;
            this.callback = callback;
        }
    }
}
