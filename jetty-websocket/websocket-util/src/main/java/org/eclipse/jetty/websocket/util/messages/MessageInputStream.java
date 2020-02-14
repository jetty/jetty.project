//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.util.messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;

/**
 * Support class for reading a WebSocket BINARY message via a InputStream.
 * <p>
 * An InputStream that can access a queue of ByteBuffer payloads, along with expected InputStream blocking behavior.
 * </p>
 */
public class MessageInputStream extends InputStream implements MessageSink
{
    private static final Logger LOG = Log.getLogger(MessageInputStream.class);
    private static final Entry EOF = new Entry(BufferUtil.EMPTY_BUFFER, Callback.NOOP);
    private final BlockingArrayQueue<Entry> buffers = new BlockingArrayQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Entry currentEntry;
    private long timeoutMs = -1;

    @Override
    public void accept(Frame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("accepting {}", frame);

        // If closed or we have no payload, request the next frame.
        if (closed.get() || (!frame.hasPayload() && !frame.isFin()))
        {
            callback.succeeded();
            return;
        }

        if (frame.hasPayload())
            buffers.add(new Entry(frame.getPayload(), callback));
        else
            callback.succeeded();

        if (frame.isFin())
            buffers.add(EOF);
    }

    @Override
    public int read() throws IOException
    {
        byte[] buf = new byte[1];
        while (true)
        {
            int len = read(buf, 0, 1);
            if (len < 0) // EOF
                return -1;
            if (len > 0) // did read something
                return buf[0];
            // reading nothing (len == 0) tries again
        }
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException
    {
        if (closed.get())
            return -1;

        Entry result = getCurrentEntry();
        if (LOG.isDebugEnabled())
            LOG.debug("result = {}", result);

        if (result == EOF)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Read EOF");
            shutdown();
            return -1;
        }

        // We have content
        int fillLen = Math.min(result.buffer.remaining(), len);
        result.buffer.get(b, off, fillLen);
        if (!result.buffer.hasRemaining())
        {
            currentEntry = null;
            result.callback.succeeded();
        }

        // return number of bytes actually copied into buffer
        return fillLen;
    }

    @Override
    public void close() throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("close()");

        if (closed.compareAndSet(false, true))
        {
            synchronized (buffers)
            {
                buffers.offer(EOF);
                buffers.notify();
            }
        }

        super.close();
    }

    private void shutdown()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("shutdown()");

        synchronized (this)
        {
            closed.set(true);
            Throwable cause = new IOException("Shutdown");
            for (Entry buffer : buffers)
            {
                buffer.callback.failed(cause);
            }

            // Removed buffers that may have remained in the queue.
            buffers.clear();
        }
    }

    public void setTimeout(long timeoutMs)
    {
        this.timeoutMs = timeoutMs;
    }

    private Entry getCurrentEntry() throws IOException
    {
        if (currentEntry != null)
            return currentEntry;

        // sync and poll queue
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Waiting {} ms to read", timeoutMs);
            if (timeoutMs < 0)
            {
                // Wait forever until a buffer is available.
                currentEntry = buffers.take();
            }
            else
            {
                // Wait at most for the given timeout.
                currentEntry = buffers.poll(timeoutMs, TimeUnit.MILLISECONDS);
                if (currentEntry == null)
                    throw new IOException(String.format("Read timeout: %,dms expired", timeoutMs));
            }
        }
        catch (InterruptedException e)
        {
            shutdown();
            throw new InterruptedIOException();
        }

        return currentEntry;
    }

    private static class Entry
    {
        public ByteBuffer buffer;
        public Callback callback;

        public Entry(ByteBuffer buffer, Callback callback)
        {
            this.buffer = Objects.requireNonNull(buffer);
            this.callback = callback;
        }

        @Override
        public String toString()
        {
            return String.format("Entry[%s,%s]", BufferUtil.toDetailString(buffer), callback.getClass().getSimpleName());
        }
    }
}
