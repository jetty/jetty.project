//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;

public class MockEndPoint implements EndPoint
{
    public static final String NOT_SUPPORTED = "Not supported by MockEndPoint";

    @Override
    public InetSocketAddress getLocalAddress()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public boolean isOpen()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public long getCreatedTimeStamp()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public void shutdownOutput()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public boolean isOutputShutdown()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public boolean isInputShutdown()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public void close()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public boolean flush(ByteBuffer... buffer) throws IOException
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public Object getTransport()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public long getIdleTimeout()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public void fillInterested(Callback callback) throws ReadPendingException
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public boolean tryFillInterested(Callback callback)
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public boolean isFillInterested()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public Connection getConnection()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public void setConnection(Connection connection)
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public void onOpen()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public void onClose()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public boolean isOptimizedForDirectBuffers()
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public void upgrade(Connection newConnection)
    {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }
}
