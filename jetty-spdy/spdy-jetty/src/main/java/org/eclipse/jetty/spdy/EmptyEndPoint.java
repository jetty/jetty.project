//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;

public class EmptyEndPoint implements EndPoint
{
    private boolean checkForIdle;
    private Connection connection;
    private boolean oshut;
    private boolean closed;
    private long maxIdleTime;

    @Override
    public long getCreatedTimeStamp()
    {
        return 0;
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
    public void shutdownOutput()
    {
        oshut = true;
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
    public void close()
    {
        closed = true;
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        return 0;
    }

    @Override
    public int flush(ByteBuffer... buffer) throws IOException
    {
        return 0;
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
        return !closed;
    }

    @Override
    public Object getTransport()
    {
        return null;
    }

    @Override
    public long getIdleTimeout()
    {
        return maxIdleTime;
    }

    @Override
    public void setIdleTimeout(long timeMs)
    {
        this.maxIdleTime = timeMs;
    }

    @Override
    public void onOpen()
    {
    }

    @Override
    public void onClose()
    {
    }

    @Override
    public <C> void fillInterested(C context, Callback<C> callback) throws ReadPendingException
    {
    }

    @Override
    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws WritePendingException
    {
    }
}
