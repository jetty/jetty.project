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
    private SuspendToken suspendToken;
    private State state = State.RESUMED;

    private enum State
    {
        RESUMED,
        SUSPENDED,
        COMPLETE,
        CLOSED
    }

    public MessageInputStream(Session session)
    {
        this(session, -1);
    }

    public MessageInputStream(Session session, int timeoutMs)
    {
        this.timeoutMs = timeoutMs;
        this.session = session;
        this.bufferPool = (session instanceof WebSocketSession) ? ((WebSocketSession)session).getBufferPool() : null;
    }

    @Override
    public void appendFrame(ByteBuffer framePayload, boolean fin) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Appending {} chunk: {}", fin ? "final" : "non-final", BufferUtil.toDetailString(framePayload));

        try
        {
            if (BufferUtil.isEmpty(framePayload))
                return;

            synchronized (this)
            {
                switch (state)
                {
                    case CLOSED:
                        LOG.warn("Received content after InputStream closed");
                        return;

                    case RESUMED:
                        suspendToken = session.suspend();
                        state = State.SUSPENDED;
                        break;

                    default:
                        throw new IllegalStateException();
                }

                // Put the payload into the queue, by copying it.
                // Copying is necessary because the payload will
                // be processed after this method returns.
                buffers.put(copy(framePayload));
            }
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
    }

    @Override
    public void close()
    {
        synchronized (this)
        {
            if (state == State.CLOSED)
                return;

            if (!buffers.isEmpty() || (activeBuffer != null && activeBuffer.hasRemaining()))
                LOG.warn("InputStream closed without fully consuming content");

            state = State.CLOSED;
            buffers.clear();
        }
    }

    @Override
    public void messageComplete()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Message completed");

        synchronized (this)
        {
            switch (state)
            {
                case CLOSED:
                    return;

                case SUSPENDED:
                case RESUMED:
                    state = State.COMPLETE;
                    break;

                default:
                    throw new IllegalStateException();
            }

            buffers.offer(EOF);
        }
    }

    public void handlerComplete()
    {
        // May need to resume to resume and read to the next message.
        SuspendToken resume;
        synchronized (this)
        {
            if (state != State.CLOSED)
            {
                if (!buffers.isEmpty() || (activeBuffer != null && activeBuffer.hasRemaining()))
                    LOG.warn("InputStream closed without fully consuming content");

                state = State.CLOSED;
                buffers.clear();
            }

            resume = suspendToken;
            suspendToken = null;
        }

        if (resume != null)
            resume.resume();
    }

    @Override
    public int read() throws IOException
    {
        try
        {
            if (state == State.CLOSED)
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
            {
                SuspendToken resume = null;
                synchronized (this)
                {
                    switch (state)
                    {
                        case CLOSED:
                            return -1;

                        case COMPLETE:
                            // If we are complete we have read the last frame but
                            // don't want to resume reading until onMessage() exits.
                            break;

                        case SUSPENDED:
                            resume = suspendToken;
                            suspendToken = null;
                            state = State.RESUMED;
                            break;

                        case RESUMED:
                            throw new IllegalStateException();
                    }
                }

                // Get more content to read.
                if (resume != null)
                    resume.resume();
            }

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

    @Override
    public void reset() throws IOException
    {
        throw new IOException("reset() not supported");
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

    private ByteBuffer copy(ByteBuffer buffer)
    {
        ByteBuffer copy = acquire(buffer.remaining(), buffer.isDirect());
        BufferUtil.clearToFill(copy);
        copy.put(buffer);
        BufferUtil.flipToFlush(copy, 0);
        return copy;
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
}
