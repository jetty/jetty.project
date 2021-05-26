//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.io.NullByteBufferPool;
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
        /**
         * Open and waiting for a frame to be delivered in {@link #appendFrame(ByteBuffer, boolean)}.
         */
        RESUMED,

        /**
         * We have suspended the session after reading a websocket frame but have not reached the end of the message.
         */
        SUSPENDED,

        /**
         * We have received a frame with fin==true and have suspended until we are signaled that onMessage method exited.
         */
        COMPLETE,

        /**
         * We have read to EOF or someone has called InputStream.close(), any further reads will result in reading -1.
         */
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
        this.bufferPool = (session instanceof WebSocketSession) ? ((WebSocketSession)session).getBufferPool() : new NullByteBufferPool();
    }

    @Override
    public void appendFrame(ByteBuffer framePayload, boolean fin) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Appending {} chunk: {}", fin ? "final" : "non-final", BufferUtil.toDetailString(framePayload));

        // Avoid entering synchronized block if there is nothing to do.
        boolean bufferIsEmpty = BufferUtil.isEmpty(framePayload);
        if (bufferIsEmpty && !fin)
            return;

        try
        {
            synchronized (this)
            {
                if (!bufferIsEmpty)
                {
                    switch (state)
                    {
                        case CLOSED:
                            return;

                        case RESUMED:
                            suspendToken = session.suspend();
                            state = State.SUSPENDED;
                            break;

                        default:
                            throw new IllegalStateException("Incorrect State: " + state.name());
                    }

                    // Put the payload into the queue, by copying it.
                    // Copying is necessary because the payload will
                    // be processed after this method returns.
                    ByteBuffer copy = acquire(framePayload.remaining(), framePayload.isDirect());
                    BufferUtil.clearToFill(copy);
                    copy.put(framePayload);
                    BufferUtil.flipToFlush(copy, 0);
                    buffers.put(copy);
                }

                if (fin)
                {
                    buffers.add(EOF);
                    state = State.COMPLETE;
                }
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

            boolean remainingContent = (state != State.COMPLETE) ||
                (!buffers.isEmpty() && buffers.peek() != EOF) ||
                (activeBuffer != null && activeBuffer.hasRemaining());

            if (remainingContent)
                LOG.warn("MessageInputStream closed without fully consuming content {}", session);


            // Release any buffers taken from the pool.
            if (activeBuffer != null && activeBuffer != EOF)
                bufferPool.release(activeBuffer);

            for (ByteBuffer buffer : buffers)
            {
                bufferPool.release(buffer);
            }

            activeBuffer = null;
            buffers.clear();
            state = State.CLOSED;
            buffers.add(EOF);
        }
    }

    public void handlerComplete()
    {
        // Close the InputStream.
        close();

        // May need to resume to resume and read to the next message.
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
    public int read() throws IOException
    {
        byte[] bytes = new byte[1];
        while (true)
        {
            int read = read(bytes, 0, 1);
            if (read < 0)
                return -1;
            if (read == 0)
                continue;

            return bytes[0] & 0xFF;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
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
            while (activeBuffer == null)
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

            ByteBuffer buffer = BufferUtil.toBuffer(b, off, len);
            BufferUtil.clearToFill(buffer);
            int written = BufferUtil.put(activeBuffer, buffer);
            BufferUtil.flipToFlush(buffer, 0);

            // If we have no more content we may need to resume to get more data.
            if (!activeBuffer.hasRemaining())
            {
                SuspendToken resume = null;
                synchronized (this)
                {
                    // Release buffer back to pool.
                    bufferPool.release(activeBuffer);
                    activeBuffer = null;

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
                            throw new IllegalStateException("Incorrect State: " + state.name());
                    }
                }

                // Get more content to read.
                if (resume != null)
                    resume.resume();
            }

            return written;
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
    public void messageComplete()
    {
        // We handle this case in appendFrame with fin==true.
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

    private ByteBuffer acquire(int capacity, boolean direct)
    {
        return bufferPool.acquire(capacity, direct);
    }
}
