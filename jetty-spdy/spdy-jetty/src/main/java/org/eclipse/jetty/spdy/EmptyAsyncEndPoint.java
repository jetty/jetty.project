//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.thread.Timeout;

public class EmptyAsyncEndPoint implements AsyncEndPoint
{
    private boolean checkForIdle;
    private Connection connection;
    private boolean oshut;
    private boolean ishut;
    private boolean closed;
    private int maxIdleTime;

    @Override
    public void dispatch()
    {
    }
    
    @Override
    public void asyncDispatch()
    {
    }

    @Override
    public void scheduleWrite()
    {
    }

    @Override
    public void onIdleExpired(long idleForMs)
    {
    }

    @Override
    public void setCheckForIdle(boolean check)
    {
        this.checkForIdle = check;
    }

    @Override
    public boolean isCheckForIdle()
    {
        return checkForIdle;
    }

    @Override
    public boolean isWritable()
    {
        return false;
    }

    @Override
    public boolean hasProgressed()
    {
        return false;
    }

    @Override
    public void scheduleTimeout(Timeout.Task task, long timeoutMs)
    {
    }

    @Override
    public void cancelTimeout(Timeout.Task task)
    {
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
    public void shutdownOutput() throws IOException
    {
        oshut = true;
    }

    @Override
    public boolean isOutputShutdown()
    {
        return oshut;
    }

    @Override
    public void shutdownInput() throws IOException
    {
        ishut = true;
    }

    @Override
    public boolean isInputShutdown()
    {
        return ishut;
    }

    @Override
    public void close() throws IOException
    {
        closed = true;
    }

    @Override
    public int fill(Buffer buffer) throws IOException
    {
        return 0;
    }

    @Override
    public int flush(Buffer buffer) throws IOException
    {
        return 0;
    }

    @Override
    public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
    {
        return 0;
    }

    @Override
    public String getLocalAddr()
    {
        return null;
    }

    @Override
    public String getLocalHost()
    {
        return null;
    }

    @Override
    public int getLocalPort()
    {
        return -1;
    }

    @Override
    public String getRemoteAddr()
    {
        return null;
    }

    @Override
    public String getRemoteHost()
    {
        return null;
    }

    @Override
    public int getRemotePort()
    {
        return -1;
    }

    @Override
    public boolean isBlocking()
    {
        return false;
    }

    @Override
    public boolean blockReadable(long millisecs) throws IOException
    {
        return false;
    }

    @Override
    public boolean blockWritable(long millisecs) throws IOException
    {
        return false;
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
    public void flush() throws IOException
    {
    }

    @Override
    public int getMaxIdleTime()
    {
        return maxIdleTime;
    }

    @Override
    public void setMaxIdleTime(int timeMs) throws IOException
    {
        this.maxIdleTime = timeMs;
    }
}
