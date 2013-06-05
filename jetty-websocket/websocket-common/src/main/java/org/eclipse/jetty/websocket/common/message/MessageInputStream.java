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

package org.eclipse.jetty.websocket.common.message;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.websocket.common.LogicalConnection;

/**
 * Support class for reading a (single) WebSocket BINARY message via a InputStream.
 * <p>
 * An InputStream that can access a queue of ByteBuffer payloads, along with expected InputStream blocking behavior.
 */
public class MessageInputStream extends InputStream implements MessageAppender
{
    /**
     * Used for controlling read suspend/resume behavior if the queue is full, but the read operations haven't caught up yet.
     */
    @SuppressWarnings("unused")
    private final LogicalConnection connection;
    private final BlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    private AtomicBoolean closed = new AtomicBoolean(false);
    // EOB / End of Buffers
    private AtomicBoolean buffersExhausted = new AtomicBoolean(false);
    private ByteBuffer activeBuffer = null;

    public MessageInputStream(LogicalConnection connection)
    {
        this.connection = connection;
    }

    @Override
    public void appendMessage(ByteBuffer payload, boolean isLast) throws IOException
    {
        if (buffersExhausted.get())
        {
            // This indicates a programming mistake/error and must be bug fixed
            throw new RuntimeException("Last frame already received");
        }

        // if closed, we should just toss incoming payloads into the bit bucket.
        if (closed.get())
        {
            return;
        }

        // Put the payload into the queue
        try
        {
            buffers.put(payload);
            if (isLast)
            {
                buffersExhausted.set(true);
            }
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
    }

    @Override
    public void close() throws IOException
    {
        closed.set(true);
        super.close();
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
        buffersExhausted.set(true);
        // toss an empty ByteBuffer into queue to let it drain
        try
        {
            buffers.put(ByteBuffer.wrap(new byte[0]));
        }
        catch (InterruptedException ignore)
        {
            /* ignore */
        }
    }

    @Override
    public int read() throws IOException
    {
        try
        {
            if (closed.get())
            {
                return -1;
            }

            if (activeBuffer == null)
            {
                activeBuffer = buffers.take();
            }

            while (activeBuffer.remaining() <= 0)
            {
                if (buffersExhausted.get())
                {
                    closed.set(true);
                    return -1;
                }
                activeBuffer = buffers.take();
            }

            return activeBuffer.get();
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
    }

    @Override
    public synchronized void reset() throws IOException
    {
        throw new IOException("reset() not supported");
    }
}
