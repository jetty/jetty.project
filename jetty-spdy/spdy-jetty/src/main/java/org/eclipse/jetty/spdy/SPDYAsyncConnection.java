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
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.AbstractAsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.IOFuture;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.parser.Parser;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class SPDYAsyncConnection extends AbstractAsyncConnection implements Controller<StandardSession.FrameBytes>, IdleListener
{
    private static final Logger logger = Log.getLogger(SPDYAsyncConnection.class);
    private final ByteBufferPool bufferPool;
    private final Parser parser;
    private volatile Session session;

    public SPDYAsyncConnection(AsyncEndPoint endPoint, ByteBufferPool bufferPool, Parser parser)
    {
        super(endPoint);
        this.bufferPool = bufferPool;
        this.parser = parser;
        onIdle(true);
    }

    @Override
    public void onReadable()
    {
        ByteBuffer buffer = bufferPool.acquire(8192, true);
        BufferUtil.clear(buffer);
        read(buffer);
        bufferPool.release(buffer);
        scheduleOnReadable();
    }

    protected void read(ByteBuffer buffer)
    {
        AsyncEndPoint endPoint = getEndPoint();
        while (true)
        {
            int filled = fill(endPoint, buffer);
            if (filled == 0)
            {
                break;
            }
            else if (filled < 0)
            {
                close(false);
                break;
            }
            else
            {
                parser.parse(buffer);
            }
        }
    }

    private int fill(AsyncEndPoint endPoint, ByteBuffer buffer)
    {
        try
        {
            return endPoint.fill(buffer);
        }
        catch (IOException x)
        {
            endPoint.close();
            throw new RuntimeIOException(x);
        }
    }

    @Override
    public int write(ByteBuffer buffer, final Callback<StandardSession.FrameBytes> callback, StandardSession.FrameBytes context)
    {
        int remaining = buffer.remaining();
        AsyncEndPoint endPoint = getEndPoint();
        IOFuture write = endPoint.write(buffer);
        int written = remaining - buffer.remaining();
        if (write.isDone())
            callback.completed(context);
        else
            write.setCallback(callback, context);
        return written;
    }

    @Override
    public void close(boolean onlyOutput)
    {
        AsyncEndPoint endPoint = getEndPoint();
        // We need to gently close first, to allow
        // SSL close alerts to be sent by Jetty
        logger.debug("Shutting down output {}", endPoint);
        endPoint.shutdownOutput();
        if (!onlyOutput)
        {
            logger.debug("Closing {}", endPoint);
            endPoint.close();
        }
    }

    @Override
    public void onIdle(boolean idle)
    {
        getEndPoint().setCheckForIdle(idle);
    }

    @Override
    public void onIdleExpired(long idleForMs)
    {
        session.goAway();
    }

    protected Session getSession()
    {
        return session;
    }

    protected void setSession(Session session)
    {
        this.session = session;
    }
}
