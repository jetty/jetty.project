//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

/**
 * Support class for reading a (single) WebSocket BINARY message via a InputStream.
 * <p>
 * An InputStream that can access a queue of ByteBuffer payloads, along with expected InputStream blocking behavior.
 */
public class MessageInputStream extends InputStream implements MessageAppender
{
    private static final Logger LOG = Log.getLogger(MessageInputStream.class);
    private static final ByteBuffer EOF = ByteBuffer.allocate(0).asReadOnlyBuffer();

    private final BlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    private AtomicBoolean closed = new AtomicBoolean(false);
    private final long timeoutMs;
    private ByteBuffer activeBuffer = null;

    public MessageInputStream()
    {
        this(-1);
    }

    public MessageInputStream(int timeoutMs)
    {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void appendFrame(ByteBuffer framePayload, boolean fin) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Appending {} chunk: {}",fin?"final":"non-final",BufferUtil.toDetailString(framePayload));
        }

        // If closed, we should just toss incoming payloads into the bit bucket.
        if (closed.get())
        {
            return;
        }

        // Put the payload into the queue, by copying it.
        // Copying is necessary because the payload will
        // be processed after this method returns.
        try
        {
            if (framePayload == null)
            {
                // skip if no payload
                return;
            }

            int capacity = framePayload.remaining();
            if (capacity <= 0)
            {
                // skip if no payload data to copy
                return;
            }
            // TODO: the copy buffer should be pooled too, but no buffer pool available from here.
            ByteBuffer copy = framePayload.isDirect()?ByteBuffer.allocateDirect(capacity):ByteBuffer.allocate(capacity);
            copy.put(framePayload).flip();
            buffers.put(copy);
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
        if (closed.compareAndSet(false, true))
        {
            buffers.offer(EOF);
            super.close();
        }
    }

    @Override
    public void mark(int readlimit)
    {
        // Not supported.
    }

    @Override
    public boolean markSupported()
    {
        return false;
    }

    @Override
    public void messageComplete()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Message completed");
        buffers.offer(EOF);
    }

    @Override
    public int read() throws IOException
    {
        try
        {
            if (closed.get())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Stream closed");
                return -1;
            }

            // grab a fresh buffer
            while (activeBuffer == null || !activeBuffer.hasRemaining())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Waiting {} ms to read", timeoutMs);
                if (timeoutMs < 0)
                {
                    // Wait forever until a buffer is available.
                    activeBuffer = buffers.take();
                }
                else
                {
                    // Wait at most for the given timeout.
                    activeBuffer = buffers.poll(timeoutMs, TimeUnit.MILLISECONDS);
                    if (activeBuffer == null)
                    {
                        throw new IOException(String.format("Read timeout: %,dms expired", timeoutMs));
                    }
                }

                if (activeBuffer == EOF)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Reached EOF");
                    // Be sure that this stream cannot be reused.
                    closed.set(true);
                    // Removed buffers that may have remained in the queue.
                    buffers.clear();
                    return -1;
                }
            }

            return activeBuffer.get() & 0xFF;
        }
        catch (InterruptedException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Interrupted while waiting to read", x);
            closed.set(true);
            return -1;
        }
    }

    @Override
    public void reset() throws IOException
    {
        throw new IOException("reset() not supported");
    }
}
