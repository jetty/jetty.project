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
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;

import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.util.Callback;

public class EmptyAsyncEndPoint implements AsyncEndPoint
{
    private boolean checkForIdle;
    private AsyncConnection connection;
    private boolean oshut;
    private boolean closed;
    private long maxIdleTime;

    @Override
    public long getCreatedTimeStamp()
    {
        return 0;
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
