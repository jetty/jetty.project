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

import org.eclipse.jetty.http3.quiche.QuicheConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.FillInterest;
import org.eclipse.jetty.io.IdleTimeout;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicStreamEndPoint extends IdleTimeout implements EndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicStreamEndPoint.class);

    private final AtomicBoolean fillable = new AtomicBoolean();
    private final FillInterest fillInterest = new FillInterest()
    {
        @Override
        protected void needsFillInterest() throws IOException
        {
            if (fillable.getAndSet(false))
                fillInterest.fillable();
        }
    };
    private final long streamId;
    private final QuicheConnection quicheConnection;
    private final QuicConnection quicConnection;
    private final InetSocketAddress localAddress;

    private InetSocketAddress remoteAddress;
    private boolean open;
    private Connection connection;

    public QuicStreamEndPoint(Scheduler scheduler, QuicheConnection quicheConnection, QuicConnection quicConnection, InetSocketAddress localAddress, InetSocketAddress remoteAddress, long streamId)
    {
        super(scheduler);
        this.streamId = streamId;
        this.quicheConnection = quicheConnection;
        this.quicConnection = quicConnection;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return localAddress;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return remoteAddress;
    }

    @Override
    public boolean isOpen()
    {
        return open;
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return 0;
    }

    @Override
    public void shutdownOutput()
    {
        try
        {
            quicheConnection.shutdownStream(streamId, true);
        }
        catch (IOException e)
        {
            LOG.warn("error shutting down output", e);
        }
    }

    @Override
    public boolean isOutputShutdown()
    {
        return false;
    }

    @Override
    public boolean isInputShutdown()
    {
        return false;
    }

    @Override
    public void close(Throwable cause)
    {
        onClose(cause);
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        BufferUtil.flipToFill(buffer);
        int drained = quicheConnection.drainClearTextForStream(streamId, buffer);
        buffer.flip();
        return drained;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        for (ByteBuffer buffer : buffers)
        {
            int fed = quicheConnection.feedClearTextForStream(streamId, buffer);
            if (fed > 0)
                quicConnection.flushCipherText(quicheConnection, remoteAddress);
            if (buffer.hasRemaining())
                return false;
        }
        return true;
    }

    @Override
    public Object getTransport()
    {
        return quicheConnection;
    }

    public Runnable onSelected(InetSocketAddress remoteAddress, boolean readable, boolean writable)
    {
        this.remoteAddress = remoteAddress;
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
        try
        {
            boolean done = flush(buffers);
            if (done)
                callback.succeeded();
        }
        catch (IOException e)
        {
            callback.failed(e);
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
