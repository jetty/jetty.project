//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.common.WebSocketSession;

/**
 * Support class for reading a (single) WebSocket BINARY message via a InputStream.
 * <p>
 * An InputStream that can access a queue of ByteBuffer payloads, along with expected InputStream blocking behavior.
 */
public class MessageInputStream extends InputStream implements MessageAppender
{
    private static final Logger LOG = Log.getLogger(MessageInputStream.class);
    private static final ByteBuffer EOF = ByteBuffer.allocate(0).asReadOnlyBuffer();

    private final Session session;
    private final ByteBufferPool bufferPool;
    private final BlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    private final long timeoutMs;
    private ByteBuffer activeBuffer = null;
    private volatile boolean closed = false;
    private volatile SuspendToken suspendToken;

    public MessageInputStream(Session session)
    {
        this(session, -1);
    }

    public MessageInputStream(Session session, int timeoutMs)
    {
        this.timeoutMs = timeoutMs;
        this.session = session;
        this.bufferPool = (session instanceof WebSocketSession) ? ((WebSocketSession)session).getBufferPool() : null;
        this.suspendToken = session.suspend();
    }

    @Override
    public void appendFrame(ByteBuffer framePayload, boolean fin) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Appending {} chunk: {}", fin ? "final" : "non-final", BufferUtil.toDetailString(framePayload));

        // If closed, we should just toss incoming payloads into the bit bucket.
        if (closed)
            return;

        // Put the payload into the queue, by copying it.
        // Copying is necessary because the payload will
        // be processed after this method returns.
        try
        {
            if (framePayload == null || !framePayload.hasRemaining())
                return;

            ByteBuffer copy = acquire(framePayload.remaining(), framePayload.isDirect());
            BufferUtil.clearToFill(copy);
            copy.put(framePayload);
            BufferUtil.flipToFlush(copy, 0);

            synchronized (this)
            {
                if (closed)
                    return;

                if (suspendToken == null)
                    suspendToken = session.suspend();
                buffers.put(copy);
            }
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
        finally
        {
            if (fin)
                buffers.offer(EOF);
        }
    }

    private ByteBuffer acquire(int capacity, boolean direct)
    {
        ByteBuffer buffer;
        if (bufferPool != null)
            buffer = bufferPool.acquire(capacity, direct);
        else
            buffer = direct ? BufferUtil.allocateDirect(capacity) : BufferUtil.allocate(capacity);
        return buffer;
    }

    @Override
    public void close()
    {
        synchronized (this)
        {
            closed = true;
            buffers.clear();
            buffers.offer(EOF);
        }

        // Resume to discard util we reach next message.
        resume();
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
            if (closed)
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
                        throw new IOException(String.format("Read timeout: %,dms expired", timeoutMs));
                }

                if (activeBuffer == EOF)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Reached EOF");

                    close();
                    return -1;
                }
            }

            int result = activeBuffer.get() & 0xFF;
            if (!activeBuffer.hasRemaining())
                resume();

            return result;
        }
        catch (InterruptedException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Interrupted while waiting to read", x);
            close();
            return -1;
        }
    }

    private void resume()
    {
        SuspendToken resume;
        synchronized (this)
        {
            resume = suspendToken;
            suspendToken = null;
        }
        if (resume != null)
            resume.resume();
    }

    @Override
    public void reset() throws IOException
    {
        throw new IOException("reset() not supported");
    }
}
