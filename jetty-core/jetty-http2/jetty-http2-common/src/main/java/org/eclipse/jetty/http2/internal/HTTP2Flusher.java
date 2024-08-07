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

package org.eclipse.jetty.http2.internal;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Flusher extends IteratingCallback implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2Flusher.class);

    private final AutoLock lock = new AutoLock();
    private final Queue<WindowEntry> windows = new ArrayDeque<>();
    private final Deque<HTTP2Session.Entry> entries = new ArrayDeque<>();
    private final Queue<HTTP2Session.Entry> pendingEntries = new ArrayDeque<>();
    private final Collection<HTTP2Session.Entry> processedEntries = new ArrayList<>();
    private final HTTP2Session session;
    private final RetainableByteBuffer.Mutable accumulator;
    private InvocationType invocationType = InvocationType.NON_BLOCKING;
    private Throwable terminated;
    private HTTP2Session.Entry stalledEntry;

    public HTTP2Flusher(HTTP2Session session)
    {
        this.session = session;
        EndPoint endPoint = session.getEndPoint();
        boolean direct = endPoint != null && endPoint.getConnection() instanceof HTTP2Connection http2Connection && http2Connection.isUseOutputDirectByteBuffers();
        this.accumulator = new RetainableByteBuffer.DynamicCapacity(session.getGenerator().getByteBufferPool(), direct, -1);
    }

    @Override
    public InvocationType getInvocationType()
    {
        return invocationType;
    }

    public void window(HTTP2Stream stream, WindowUpdateFrame frame)
    {
        Throwable closed;
        try (AutoLock ignored = lock.lock())
        {
            closed = terminated;
            if (closed == null)
                windows.offer(new WindowEntry(stream, frame));
        }
        // Flush stalled data.
        if (closed == null)
            iterate();
    }

    public boolean prepend(HTTP2Session.Entry entry)
    {
        Throwable closed;
        try (AutoLock ignored = lock.lock())
        {
            closed = terminated;
            if (closed == null)
            {
                entries.offerFirst(entry);
                if (LOG.isDebugEnabled())
                    LOG.debug("Prepended {}, entries={}", entry, entries.size());
            }
        }
        if (closed == null)
            return true;
        closed(entry, closed);
        return false;
    }

    public boolean append(HTTP2Session.Entry entry)
    {
        Throwable closed;
        try (AutoLock ignored = lock.lock())
        {
            closed = terminated;
            if (closed == null)
            {
                entries.offer(entry);
                if (LOG.isDebugEnabled())
                    LOG.debug("Appended {}, entries={}, {}", entry, entries.size(), this);
            }
        }
        if (closed == null)
            return true;
        closed(entry, closed);
        return false;
    }

    public boolean append(List<HTTP2Session.Entry> list)
    {
        Throwable closed;
        try (AutoLock ignored = lock.lock())
        {
            closed = terminated;
            if (closed == null)
            {
                list.forEach(entries::offer);
                if (LOG.isDebugEnabled())
                    LOG.debug("Appended {}, entries={} {}", list, entries.size(), this);
            }
        }
        if (closed == null)
            return true;
        list.forEach(entry -> closed(entry, closed));
        return false;
    }

    private int getWindowQueueSize()
    {
        try (AutoLock ignored = lock.lock())
        {
            return windows.size();
        }
    }

    public int getFrameQueueSize()
    {
        try (AutoLock ignored = lock.lock())
        {
            return entries.size();
        }
    }

    @Override
    protected Action process() throws Throwable
    {
        if (LOG.isDebugEnabled())
            LOG.debug("process {} {}", session, this);

        try (AutoLock ignored = lock.lock())
        {
            if (terminated != null)
                throw terminated;

            WindowEntry windowEntry;
            while ((windowEntry = windows.poll()) != null)
            {
                windowEntry.perform();
            }

            HTTP2Session.Entry entry;
            while ((entry = entries.poll()) != null)
            {
                pendingEntries.offer(entry);
            }
        }

        if (pendingEntries.isEmpty())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Flushed {} {}", session, this);
            return Action.IDLE;
        }

        while (true)
        {
            boolean progress = false;

            if (pendingEntries.isEmpty())
                break;

            Iterator<HTTP2Session.Entry> pending = pendingEntries.iterator();
            while (pending.hasNext())
            {
                HTTP2Session.Entry entry = pending.next();
                if (LOG.isDebugEnabled())
                    LOG.debug("Processing {}", entry);

                // If the stream has been reset or removed,
                // don't send the frame and fail it here.
                if (entry.shouldBeDropped())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Dropped {}", entry);
                    entry.failed(new EofException("dropped"));
                    pending.remove();
                    continue;
                }

                try
                {
                    if (entry.generate(accumulator))
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Generated {} frame bytes for {}", entry.getFrameBytesGenerated(), entry);

                        progress = true;

                        // We use ArrayList contains() + add() instead of HashSet add()
                        // because that is faster for collections of size up to 250 entries.
                        if (!processedEntries.contains(entry))
                        {
                            processedEntries.add(entry);
                            invocationType = Invocable.combine(invocationType, Invocable.getInvocationType(entry.getCallback()));
                        }

                        if (entry.getDataBytesRemaining() == 0)
                            pending.remove();
                    }
                    else
                    {
                        if (session.getSendWindow() <= 0 && stalledEntry == null)
                        {
                            stalledEntry = entry;
                            if (LOG.isDebugEnabled())
                                LOG.debug("Flow control stalled at {}", entry);
                            // Continue to process control frames.
                        }
                    }
                }
                catch (HpackException.StreamException failure)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Failure generating {}", entry, failure);
                    entry.failed(failure);
                    pending.remove();
                }
                catch (Throwable failure)
                {
                    // Failure to generate the entry is catastrophic.
                    if (LOG.isDebugEnabled())
                        LOG.debug("Failure generating {}", entry, failure);
                    failed(failure);
                    return Action.SCHEDULED;
                }
            }

            if (!progress)
                break;

            if (stalledEntry != null)
                break;

            int writeThreshold = session.getWriteThreshold();
            if (accumulator.size() >= writeThreshold)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Write threshold {} exceeded", writeThreshold);
                break;
            }
        }

        if (accumulator.isEmpty())
        {
            finish();
            return Action.IDLE;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Writing {} bytes - entries processed/pending {}/{}: {}/{}",
                accumulator.size(),
                processedEntries.size(),
                pendingEntries.size(),
                processedEntries,
                pendingEntries);

        accumulator.writeTo(session.getEndPoint(), false, this);
        return Action.SCHEDULED;
    }

    @Override
    protected void onSuccess()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Written - entries processed/pending {}/{}: {}/{}",
                processedEntries.size(),
                pendingEntries.size(),
                processedEntries,
                pendingEntries);
        finish();
    }

    private void finish()
    {
        accumulator.clear();
        processedEntries.forEach(HTTP2Session.Entry::succeeded);
        processedEntries.clear();
        invocationType = InvocationType.NON_BLOCKING;

        if (stalledEntry != null)
        {
            int size = pendingEntries.size();
            for (int i = 0; i < size; ++i)
            {
                HTTP2Session.Entry entry = pendingEntries.peek();
                if (entry == stalledEntry)
                    break;
                pendingEntries.poll();
                pendingEntries.offer(entry);
            }
            stalledEntry = null;
        }
    }

    @Override
    protected void onCompleteSuccess()
    {
        throw new IllegalStateException();
    }

    @Override
    protected void onCompleteFailure(Throwable x)
    {
        accumulator.release();

        Throwable closed;
        Set<HTTP2Session.Entry> allEntries;
        try (AutoLock ignored = lock.lock())
        {
            closed = terminated;
            terminated = x;
            if (LOG.isDebugEnabled())
                LOG.debug(String.format("%s, entries processed/pending/queued=%d/%d/%d",
                    closed != null ? "Closing" : "Failing",
                    processedEntries.size(),
                    pendingEntries.size(),
                    entries.size()), x);
            allEntries = new HashSet<>(entries);
            entries.clear();
        }

        allEntries.addAll(processedEntries);
        processedEntries.clear();
        allEntries.addAll(pendingEntries);
        pendingEntries.clear();
        allEntries.forEach(entry -> entry.failed(x));

        // If the failure came from within the
        // flusher, we need to close the connection.
        if (closed == null)
            session.onWriteFailure(x);
    }

    public void terminate(Throwable cause)
    {
        Throwable closed;
        try (AutoLock ignored = lock.lock())
        {
            closed = terminated;
            terminated = cause;
            if (LOG.isDebugEnabled())
                LOG.debug("{} {}", closed != null ? "Terminated" : "Terminating", this);
        }
        if (closed == null)
            iterate();
    }

    private void closed(HTTP2Session.Entry entry, Throwable failure)
    {
        entry.failed(failure);
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(toString()).append(System.lineSeparator());
    }

    @Override
    public String toString()
    {
        return String.format("%s[window_queue=%d,frame_queue=%d,processed/pending=%d/%d]",
            super.toString(),
            getWindowQueueSize(),
            getFrameQueueSize(),
            processedEntries.size(),
            pendingEntries.size());
    }

    private class WindowEntry
    {
        private final HTTP2Stream stream;
        private final WindowUpdateFrame frame;

        public WindowEntry(HTTP2Stream stream, WindowUpdateFrame frame)
        {
            this.stream = stream;
            this.frame = frame;
        }

        public void perform()
        {
            FlowControlStrategy flowControl = session.getFlowControlStrategy();
            flowControl.onWindowUpdate(session, stream, frame);
        }
    }
}
