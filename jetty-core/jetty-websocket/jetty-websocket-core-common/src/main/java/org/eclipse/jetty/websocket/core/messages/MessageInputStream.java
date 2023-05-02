//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Support class for reading a WebSocket BINARY message via a InputStream.
 * <p>
 * An InputStream that can access a queue of ByteBuffer payloads, along with expected InputStream blocking behavior.
 * </p>
 */
public class MessageInputStream extends InputStream implements MessageSink
{
    private static final Logger LOG = LoggerFactory.getLogger(MessageInputStream.class);
    private static final Entry EOF = new Entry(null, Callback.NOOP);
    private static final Entry CLOSED = new Entry(null, Callback.NOOP);
    private static final Entry FAILED = new Entry(null, Callback.NOOP);

    private final AutoLock.WithCondition lock = new AutoLock.WithCondition();
    private final ArrayDeque<Entry> buffers = new ArrayDeque<>();
    private final CoreSession session;
    private Entry currentEntry;
    private Throwable failure;
    private boolean closed;
    private long timeoutMs = -1;

    public MessageInputStream(CoreSession session)
    {
        this.session = session;
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("accepting {}", frame);

        if (!frame.isFin() && !frame.hasPayload())
        {
            callback.succeeded();
            session.demand(1);
            return;
        }

        Runnable action = null;
        try (AutoLock.WithCondition l = lock.lock())
        {
            if (failure != null)
            {
                Throwable cause = failure;
                action = () -> callback.failed(cause);
            }
            else if (closed)
            {
                action = callback::succeeded;
            }
            else
            {
                buffers.offer(new Entry(frame, callback));
                if (frame.isFin())
                    buffers.offer(EOF);
            }
            l.signal();
        }

        if (action != null)
            action.run();
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
                return buf[0] & 0xFF;
            // reading nothing (len == 0) tries again
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        ByteBuffer buffer = ByteBuffer.wrap(b, off, len).slice();
        BufferUtil.clear(buffer);
        return read(buffer);
    }

    public int read(ByteBuffer buffer) throws IOException
    {
        Entry currentEntry = getCurrentEntry();
        if (LOG.isDebugEnabled())
            LOG.debug("currentEntry = {}", currentEntry);

        if (currentEntry == FAILED)
            throw IO.rethrow(getFailure());

        if (currentEntry == CLOSED)
            throw new IOException("Closed");

        if (currentEntry == EOF)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Read EOF");
            return -1;
        }

        ByteBuffer payload = currentEntry.frame.getPayload();
        if (currentEntry.frame.isFin() && !payload.hasRemaining())
        {
            succeedCurrentEntry();
            // Recurse to avoid returning 0, as now EOF will be found.
            return read(buffer);
        }

        int length = BufferUtil.append(buffer, payload);
        if (!payload.hasRemaining())
            succeedCurrentEntry();

        // Return number of bytes copied into the buffer.
        if (LOG.isDebugEnabled())
            LOG.debug("filled {} bytes from {}", length, currentEntry);
        return length;
    }

    @Override
    public void fail(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("fail()", failure);

        ArrayList<Entry> entries = new ArrayList<>();
        try (AutoLock.WithCondition l = lock.lock())
        {
            if (this.failure != null)
                return;
            this.failure = failure;

            drainInto(entries);
            buffers.offer(FAILED);
            l.signal();
        }

        entries.forEach(e -> e.callback.failed(failure));
    }

    @Override
    public void close()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("close()");

        ArrayList<Entry> entries = new ArrayList<>();
        try (AutoLock.WithCondition l = lock.lock())
        {
            if (closed)
                return;
            closed = true;

            drainInto(entries);
            buffers.offer(CLOSED);
            l.signal();
        }

        entries.forEach(e -> e.callback.succeeded());
    }

    private void drainInto(ArrayList<Entry> entries)
    {
        assert lock.isHeldByCurrentThread();

        if (currentEntry != null)
        {
            entries.add(currentEntry);
            currentEntry = null;
        }

        // Drain the queue.
        entries.addAll(buffers);
        buffers.clear();
    }

    public void setTimeout(long timeoutMs)
    {
        this.timeoutMs = timeoutMs;
    }

    private void succeedCurrentEntry()
    {
        Entry current;
        try (AutoLock ignored = lock.lock())
        {
            current = currentEntry;
            currentEntry = null;
        }
        if (current != null)
        {
            current.callback.succeeded();
            if (!current.frame.isFin())
                session.demand(1);
        }
    }

    private Entry getCurrentEntry() throws IOException
    {
        try (AutoLock.WithCondition l = lock.lock())
        {
            if (currentEntry != null)
                return currentEntry;

            long timeout = timeoutMs;
            if (LOG.isDebugEnabled())
                LOG.debug("Waiting {} ms to read", timeout);

            Entry result;
            while (true)
            {
                result = buffers.poll();
                if (result != null)
                    break;

                if (timeout < 0)
                    l.await();
                else if (!l.await(timeout, TimeUnit.MILLISECONDS))
                    throw new IOException(String.format("Read timeout: %,dms expired", timeout));
            }

            return currentEntry = result;
        }
        catch (InterruptedException e)
        {
            close();
            throw new InterruptedIOException();
        }
    }

    private Throwable getFailure()
    {
        try (AutoLock ignored = lock.lock())
        {
            return failure;
        }
    }

    private record Entry(Frame frame, Callback callback)
    {
    }
}
