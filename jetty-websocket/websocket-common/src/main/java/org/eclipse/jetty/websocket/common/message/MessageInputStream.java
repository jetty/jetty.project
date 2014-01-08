//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.message;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.LogicalConnection;

/**
 * Support class for reading a (single) WebSocket BINARY message via a InputStream.
 * <p>
 * An InputStream that can access a queue of ByteBuffer payloads, along with expected InputStream blocking behavior.
 */
public class MessageInputStream extends InputStream implements MessageAppender
{
    private static final Logger LOG = Log.getLogger(MessageInputStream.class);
    // EOF (End of Buffers)
    private final static ByteBuffer EOF = ByteBuffer.allocate(0).asReadOnlyBuffer();
    /**
     * Used for controlling read suspend/resume behavior if the queue is full, but the read operations haven't caught up yet.
     */
    @SuppressWarnings("unused")
    private final LogicalConnection connection;
    private final BlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    private AtomicBoolean closed = new AtomicBoolean(false);
    private ByteBuffer activeBuffer = null;
    private long timeoutMs = -1;

    public MessageInputStream(LogicalConnection connection)
    {
        this.connection = connection;
        this.timeoutMs = -1; // disabled
    }
    
    public MessageInputStream(LogicalConnection connection, int timeoutMs)
    {
        this.connection = connection;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void appendFrame(ByteBuffer framePayload, boolean fin) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("appendMessage(ByteBuffer,{}): {}",fin,BufferUtil.toDetailString(framePayload));
        }

        // if closed, we should just toss incoming payloads into the bit bucket.
        if (closed.get())
        {
            return;
        }

        // Put the payload into the queue
        try
        {
            buffers.put(framePayload);
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
        finally
        {
            if (fin)
            {
                buffers.offer(EOF);
            }
        }
    }

    @Override
    public void close() throws IOException
    {
        if (closed.compareAndSet(false,true))
        {
            buffers.offer(EOF);
            super.close();
        }
    }

    @Override
    public synchronized void mark(int readlimit)
    {
        /* do nothing */
    }

    @Override
    public boolean markSupported()
    {
        return false;
    }

    @Override
    public void messageComplete()
    {
        LOG.debug("messageComplete()");

        // toss an empty ByteBuffer into queue to let it drain
        buffers.offer(EOF);
    }

    @Override
    public int read() throws IOException
    {
        LOG.debug("read()");

        try
        {
            if (closed.get())
            {
                return -1;
            }

            // grab a fresh buffer
            while (activeBuffer == null || !activeBuffer.hasRemaining())
            {
                if (timeoutMs == -1)
                {
                    // infinite take
                    activeBuffer = buffers.take();
                }
                else
                {
                    // timeout specific
                    activeBuffer = buffers.poll(timeoutMs,TimeUnit.MILLISECONDS);
                    if (activeBuffer == null)
                    {
                        throw new IOException(String.format("Read timeout: %,dms expired",timeoutMs));
                    }
                }
                
                if (activeBuffer == EOF)
                {
                    closed.set(true);
                    return -1;
                }
            }

            return activeBuffer.get();
        }
        catch (InterruptedException e)
        {
            LOG.warn(e);
            closed.set(true);
            return -1;
            // throw new IOException(e);
        }
    }

    @Override
    public synchronized void reset() throws IOException
    {
        throw new IOException("reset() not supported");
    }
}
