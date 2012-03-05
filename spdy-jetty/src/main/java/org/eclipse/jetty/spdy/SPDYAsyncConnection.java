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

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.NIOBuffer;
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SPDYAsyncConnection extends AbstractConnection implements AsyncConnection, Controller<StandardSession.FrameBytes>, IdleListener
{
    private static final Logger logger = LoggerFactory.getLogger(SPDYAsyncConnection.class);
    private final ByteBufferPool bufferPool;
    private final Parser parser;
    private volatile Session session;
    private ByteBuffer writeBuffer;
    private Handler<StandardSession.FrameBytes> writeHandler;
    private StandardSession.FrameBytes writeContext;
    private volatile boolean writePending;

    public SPDYAsyncConnection(AsyncEndPoint endPoint, ByteBufferPool bufferPool, Parser parser)
    {
        super(endPoint);
        this.bufferPool = bufferPool;
        this.parser = parser;
        onIdle(true);
    }

    @Override
    public Connection handle() throws IOException
    {
        AsyncEndPoint endPoint = getEndPoint();
        boolean progress = true;
        while (endPoint.isOpen() && progress)
        {
            int filled = fill();
            progress = filled > 0;

            int flushed = flush();
            progress |= flushed > 0;

            endPoint.flush();

            progress |= endPoint.hasProgressed();

            if (!progress && filled < 0)
            {
                onInputShutdown();
                close(false);
            }
        }
        return this;
    }

    public int fill() throws IOException
    {
        ByteBuffer buffer = bufferPool.acquire(8192, true);
        NIOBuffer jettyBuffer = new DirectNIOBuffer(buffer, false);
        jettyBuffer.setPutIndex(jettyBuffer.getIndex());
        AsyncEndPoint endPoint = getEndPoint();
        int filled = endPoint.fill(jettyBuffer);
        logger.debug("Filled {} from {}", filled, endPoint);
        if (filled <= 0)
            return filled;

        buffer.limit(jettyBuffer.putIndex());
        buffer.position(jettyBuffer.getIndex());
        parser.parse(buffer);

        bufferPool.release(buffer);

        return filled;
    }

    public int flush()
    {
        int result = 0;
        // Volatile read to ensure visibility of buffer and handler
        if (writePending)
            result = write(writeBuffer, writeHandler, writeContext);
        logger.debug("Flushed {} to {}", result, getEndPoint());
        return result;
    }

    @Override
    public int write(ByteBuffer buffer, Handler<StandardSession.FrameBytes> handler, StandardSession.FrameBytes context)
    {
        int remaining = buffer.remaining();
        Buffer jettyBuffer = buffer.isDirect() ? new DirectNIOBuffer(buffer, false) : new IndirectNIOBuffer(buffer, false);
        AsyncEndPoint endPoint = getEndPoint();
        try
        {
            int written = endPoint.flush(jettyBuffer);
            logger.debug("Written {} bytes, {} remaining", written, jettyBuffer.length());
        }
        catch (Exception x)
        {
            close(false);
            handler.failed(x);
        }
        finally
        {
            buffer.limit(jettyBuffer.putIndex());
            buffer.position(jettyBuffer.getIndex());
        }

        if (buffer.hasRemaining())
        {
            // Save buffer and handler in order to finish the write later in flush()
            this.writeBuffer = buffer;
            this.writeHandler = handler;
            this.writeContext = context;
            // Volatile write to ensure visibility of write fields
            writePending = true;
            endPoint.scheduleWrite();
        }
        else
        {
            if (writePending)
            {
                this.writeBuffer = null;
                this.writeHandler = null;
                this.writeContext = null;
                // Volatile write to ensure visibility of write fields
                writePending = false;
            }
            handler.completed(context);
        }

        return remaining - buffer.remaining();
    }

    @Override
    public void close(boolean onlyOutput)
    {
        try
        {
            AsyncEndPoint endPoint = getEndPoint();
            try
            {
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
            catch (IOException x)
            {
                endPoint.close();
            }
        }
        catch (IOException x)
        {
            logger.trace("", x);
        }
    }

    @Override
    public void onIdle(boolean idle)
    {
        getEndPoint().setCheckForIdle(idle);
    }

    @Override
    public AsyncEndPoint getEndPoint()
    {
        return (AsyncEndPoint)super.getEndPoint();
    }

    @Override
    public boolean isIdle()
    {
        return false;
    }

    @Override
    public boolean isSuspended()
    {
        return false;
    }

    @Override
    public void onClose()
    {
    }

    @Override
    public void onInputShutdown() throws IOException
    {
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
