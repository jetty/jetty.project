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

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
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
    private static final Entry EOF = new Entry(null, Callback.NOOP, true);
    private static final Entry CLOSED = new Entry(null, Callback.NOOP, true);
    private static final Entry FAILED = new Entry(null, Callback.NOOP, true);

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
            session.demand();
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
                action = () ->
                {
                    callback.succeeded();
                    if (!frame.isFin())
                        session.demand();
                };
            }
            else
            {
                // The frame is stored to be read later by another thread.
                frame.retain();
                buffers.offer(new Entry(frame, callback, false));
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
        return read(buffer);
    }

    public int read(ByteBuffer buffer) throws IOException
    {
        try (Entry currentEntry = takeEntry())
        {
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

            try (RetainableByteBuffer payload = currentEntry.frame.acquire())
            {
                int length = payload.appendTo(buffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} bytes from {}", length, currentEntry);

                if (payload.hasRemaining())
                {
                    // The buffer is full, the rest of the entry is read later.
                    storeEntry(currentEntry);
                    return length;
                }

                succeed(currentEntry);

                // Avoid returning 0 for an empty fin frame, read again to find EOF.
                if (length == 0)
                    return read(buffer);

                return length;
            }
        }
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

        entries.forEach(e ->
        {
            e.callback.failed(failure);
            e.close();
        });
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

        boolean demandRequired = false;
        for (Entry e : entries)
        {
            e.callback.succeeded();
            if (e.frame != null && !e.frame.isFin())
                demandRequired = true;
            e.close();
        }
        if (demandRequired)
            session.demand();
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

    private void succeed(Entry entry)
    {
        entry.callback.succeeded();
        if (!entry.frame.isFin())
            session.demand();
    }

    private Entry takeEntry() throws IOException
    {
        try (AutoLock.WithCondition l = lock.lock())
        {
            Entry entry = currentEntry;
            if (entry != null && !entry.terminal())
                currentEntry = null;
            if (entry != null)
                return entry;

            while (true)
            {
                entry = buffers.poll();
                if (entry != null)
                {
                    if (entry.terminal())
                        currentEntry = entry;
                    return entry;
                }

                long timeout = timeoutMs;
                if (LOG.isDebugEnabled())
                    LOG.debug("Waiting {} ms to read", timeout);

                if (timeout < 0)
                    l.await();
                else if (!l.await(timeout, TimeUnit.MILLISECONDS))
                    throw new IOException(String.format("Read timeout: %,dms expired", timeout));
            }
        }
        catch (InterruptedException x)
        {
            close();
            throw new InterruptedIOException();
        }
    }

    private void storeEntry(Entry entry)
    {
        Throwable cause;
        try (AutoLock ignored = lock.lock())
        {
            if (entry.terminal())
                return;

            if (!closed && failure == null)
            {
                entry.frame.retain();
                currentEntry = entry;
                return;
            }

            cause = failure;
        }

        // Closed or failed while the entry was taken:
        // complete it like close() or fail() would have done.
        if (cause != null)
        {
            entry.callback.failed(cause);
        }
        else
        {
            entry.callback.succeeded();
            if (!entry.frame.isFin())
                session.demand();
        }
    }

    private Throwable getFailure()
    {
        try (AutoLock ignored = lock.lock())
        {
            return failure;
        }
    }

    private record Entry(Frame frame, Callback callback, boolean terminal) implements AutoCloseable
    {
        @Override
        public void close()
        {
            if (frame != null)
                frame.release();
        }
    }
}
