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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.IOFuture;

public class EmptyAsyncEndPoint implements AsyncEndPoint
{
    private boolean checkForIdle;
    private AsyncConnection connection;
    private boolean oshut;
    private boolean closed;
    private int maxIdleTime;

    @Override
    public long getCreatedTimeStamp()
    {
        return 0;
    }

    @Override
    public long getIdleTimestamp()
    {
        return 0;
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
    public AsyncConnection getAsyncConnection()
    {
        return connection;
    }

    @Override
    public void setAsyncConnection(AsyncConnection connection)
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
    public IOFuture readable() throws IllegalStateException
    {
        return null;
    }

    @Override
    public int flush(ByteBuffer... buffer) throws IOException
    {
        return 0;
    }

    @Override
    public IOFuture write(ByteBuffer... buffers) throws IllegalStateException
    {
        return null;
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
