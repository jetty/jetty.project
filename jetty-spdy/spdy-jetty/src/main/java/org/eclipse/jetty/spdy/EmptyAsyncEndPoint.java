/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
