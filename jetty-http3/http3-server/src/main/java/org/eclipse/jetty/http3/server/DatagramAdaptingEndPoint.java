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

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;

public class DatagramAdaptingEndPoint implements EndPoint
{
    private final ServerDatagramEndPoint delegate;
    private InetSocketAddress remoteAddress;

    public DatagramAdaptingEndPoint(ServerDatagramEndPoint delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return delegate.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return remoteAddress;
    }

    @Override
    public boolean isOpen()
    {
        return delegate.isOpen();
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return delegate.getCreatedTimeStamp();
    }

    @Override
    public void shutdownOutput()
    {
        delegate.shutdownOutput();
    }

    @Override
    public boolean isOutputShutdown()
    {
        return delegate.isOutputShutdown();
    }

    @Override
    public boolean isInputShutdown()
    {
        return delegate.isInputShutdown();
    }

    @Override
    public void close(Throwable cause)
    {
        delegate.close(cause);
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        int filled = delegate.fill(buffer);
        if (filled == 0)
            return 0;

        remoteAddress = ServerDatagramEndPoint.decodeInetSocketAddress(buffer);
        return filled;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        return delegate.flush(buffers);
    }

    @Override
    public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
    {
        ByteBuffer[] delegateBuffers = new ByteBuffer[buffers.length + 1];
        System.arraycopy(buffers, 0, delegateBuffers, 1, buffers.length);

        delegateBuffers[0] = ByteBuffer.allocate(ServerDatagramEndPoint.ENCODED_ADDRESS_LENGTH);
        try
        {
            ServerDatagramEndPoint.encodeInetSocketAddress(delegateBuffers[0], remoteAddress);
        }
        catch (IOException e)
        {
            throw new IllegalArgumentException(e);
        }
        delegateBuffers[0].position(0);

        delegate.write(callback, delegateBuffers);
    }

    @Override
    public Object getTransport()
    {
        return delegate.getTransport();
    }

    @Override
    public long getIdleTimeout()
    {
        return delegate.getIdleTimeout();
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        delegate.setIdleTimeout(idleTimeout);
    }

    @Override
    public void fillInterested(Callback callback) throws ReadPendingException
    {
        delegate.fillInterested(callback);
    }

    @Override
    public boolean tryFillInterested(Callback callback)
    {
        return delegate.tryFillInterested(callback);
    }

    @Override
    public boolean isFillInterested()
    {
        return delegate.isFillInterested();
    }

    @Override
    public Connection getConnection()
    {
        return delegate.getConnection();
    }

    @Override
    public void setConnection(Connection connection)
    {
        delegate.setConnection(connection);
    }

    @Override
    public void onOpen()
    {
        delegate.onOpen();
    }

    @Override
    public void onClose(Throwable cause)
    {
        delegate.onClose(cause);
    }

    @Override
    public void upgrade(Connection newConnection)
    {
        delegate.upgrade(newConnection);
    }
}
