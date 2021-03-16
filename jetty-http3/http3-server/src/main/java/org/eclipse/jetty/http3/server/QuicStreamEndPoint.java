//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.FillInterest;
import org.eclipse.jetty.io.IdleTimeout;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicStreamEndPoint extends IdleTimeout implements EndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicStreamEndPoint.class);

    private final long createdTimeStamp = System.currentTimeMillis();
    private final AtomicBoolean fillable = new AtomicBoolean();
    private final FillInterest fillInterest = new FillInterest()
    {
        @Override
        protected void needsFillInterest()
        {
            if (fillable.getAndSet(false))
                fillInterest.fillable();
        }
    };
    private final WriteFlusher writeFlusher = new WriteFlusher(this)
    {
        @Override
        protected void onIncompleteFlush()
        {
            // No need to do anything.
            // See QuicSession.process().
        }
    };
    private final QuicSession session;
    private Connection connection;
    private final long streamId;
    private boolean open;

    public QuicStreamEndPoint(Scheduler scheduler, QuicSession session, long streamId)
    {
        super(scheduler);
        this.session = session;
        this.streamId = streamId;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return session.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return session.getRemoteAddress();
    }

    @Override
    public boolean isOpen()
    {
        return open;
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return createdTimeStamp;
    }

    @Override
    public void shutdownOutput()
    {
        try
        {
            session.shutdownOutput(streamId);
        }
        catch (IOException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("error shutting down output", x);
        }
    }

    @Override
    public boolean isOutputShutdown()
    {
        return session.isOutputShutdown(streamId);
    }

    @Override
    public boolean isInputShutdown()
    {
        return session.isInputShutdown(streamId);
    }

    @Override
    public void close(Throwable cause)
    {
        onClose(cause);
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        int pos = BufferUtil.flipToFill(buffer);
        int drained = session.fill(streamId, buffer);
        BufferUtil.flipToFlush(buffer, pos);
        return drained;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        for (ByteBuffer buffer : buffers)
        {
            int flushed = session.flush(streamId, buffer);
            if (LOG.isDebugEnabled())
                LOG.debug("flushed {} bytes", flushed);
            if (buffer.hasRemaining())
                return false;
        }
        return true;
    }

    @Override
    public Object getTransport()
    {
        return session;
    }

    public void onWritable()
    {
        writeFlusher.completeWrite();
    }

    public Runnable onReadable()
    {
        return () ->
        {
            //TODO: this is racy
            if (!fillInterest.fillable())
                fillable.set(true);
        };
    }

    @Override
    public void fillInterested(Callback callback) throws ReadPendingException
    {
        fillInterest.register(callback);
    }

    @Override
    public boolean tryFillInterested(Callback callback)
    {
        return fillInterest.tryRegister(callback);
    }

    @Override
    public boolean isFillInterested()
    {
        return fillInterest.isInterested();
    }

    @Override
    public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
    {
        writeFlusher.write(callback, buffers);
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
        open = true;
    }

    @Override
    public void onClose(Throwable cause)
    {
        open = false;
    }

    @Override
    public void upgrade(Connection newConnection)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void onIdleExpired(TimeoutException timeout)
    {

    }
}
