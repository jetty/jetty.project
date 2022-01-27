//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Flusher extends IteratingCallback implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2Flusher.class);
    private static final ByteBuffer[] EMPTY_BYTE_BUFFERS = new ByteBuffer[0];

    private final AutoLock lock = new AutoLock();
    private final Queue<WindowEntry> windows = new ArrayDeque<>();
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Queue<Entry> pendingEntries = new ArrayDeque<>();
    private final Collection<Entry> processedEntries = new ArrayList<>();
    private final HTTP2Session session;
    private final ByteBufferPool.Lease lease;
    private InvocationType invocationType = InvocationType.NON_BLOCKING;
    private Throwable terminated;
    private Entry stalledEntry;

    public HTTP2Flusher(HTTP2Session session)
    {
        this.session = session;
        this.lease = new ByteBufferPool.Lease(session.getGenerator().getByteBufferPool());
    }

    @Override
    public InvocationType getInvocationType()
    {
        return invocationType;
    }

    public void window(IStream stream, WindowUpdateFrame frame)
    {
        Throwable closed;
        try (AutoLock l = lock.lock())
        {
            closed = terminated;
            if (closed == null)
                windows.offer(new WindowEntry(stream, frame));
        }
        // Flush stalled data.
        if (closed == null)
            iterate();
    }

    public boolean prepend(Entry entry)
    {
        Throwable closed;
        try (AutoLock l = lock.lock())
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

    public boolean append(Entry entry)
    {
        Throwable closed;
        try (AutoLock l = lock.lock())
        {
            closed = terminated;
            if (closed == null)
            {
                entries.offer(entry);
                if (LOG.isDebugEnabled())
                    LOG.debug("Appended {}, entries={}", entry, entries.size());
            }
        }
        if (closed == null)
            return true;
        closed(entry, closed);
        return false;
    }

    public boolean append(List<Entry> list)
    {
        Throwable closed;
        try (AutoLock l = lock.lock())
        {
            closed = terminated;
            if (closed == null)
            {
                list.forEach(entries::offer);
                if (LOG.isDebugEnabled())
                    LOG.debug("Appended {}, entries={}", list, entries.size());
            }
        }
        if (closed == null)
            return true;
        list.forEach(entry -> closed(entry, closed));
        return false;
    }

    private int getWindowQueueSize()
    {
        try (AutoLock l = lock.lock())
        {
            return windows.size();
        }
    }

    public int getFrameQueueSize()
    {
        try (AutoLock l = lock.lock())
        {
            return entries.size();
        }
    }

    @Override
    protected Action process() throws Throwable
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Flushing {}", session);

        try (AutoLock l = lock.lock())
        {
            if (terminated != null)
                throw terminated;

            WindowEntry windowEntry;
            while ((windowEntry = windows.poll()) != null)
            {
                windowEntry.perform();
            }

            Entry entry;
            while ((entry = entries.poll()) != null)
            {
                pendingEntries.offer(entry);
            }
        }

        if (pendingEntries.isEmpty())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Flushed {}", session);
            return Action.IDLE;
        }

        while (true)
        {
            boolean progress = false;

            if (pendingEntries.isEmpty())
                break;

            Iterator<Entry> pending = pendingEntries.iterator();
            while (pending.hasNext())
            {
                Entry entry = pending.next();
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
                    if (entry.generate(lease))
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
                    return Action.SUCCEEDED;
                }
            }

            if (!progress)
                break;

            if (stalledEntry != null)
                break;

            int writeThreshold = session.getWriteThreshold();
            if (lease.getTotalLength() >= writeThreshold)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Write threshold {} exceeded", writeThreshold);
                break;
            }
        }

        List<ByteBuffer> byteBuffers = lease.getByteBuffers();
        if (byteBuffers.isEmpty())
        {
            finish();
            return Action.IDLE;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Writing {} buffers ({} bytes) - entries processed/pending {}/{}: {}/{}",
                byteBuffers.size(),
                lease.getTotalLength(),
                processedEntries.size(),
                pendingEntries.size(),
                processedEntries,
                pendingEntries);

        session.getEndPoint().write(this, byteBuffers.toArray(EMPTY_BYTE_BUFFERS));
        return Action.SCHEDULED;
    }

    void onFlushed(long bytes) throws IOException
    {
        // A single EndPoint write may be flushed multiple times (for example with SSL).
        for (Entry entry : processedEntries)
        {
            bytes = entry.onFlushed(bytes);
        }
    }

    @Override
    public void succeeded()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Written {} buffers - entries processed/pending {}/{}: {}/{}",
                lease.getByteBuffers().size(),
                processedEntries.size(),
                pendingEntries.size(),
                processedEntries,
                pendingEntries);
        finish();
        super.succeeded();
    }

    private void finish()
    {
        lease.recycle();

        processedEntries.forEach(Entry::succeeded);
        processedEntries.clear();
        invocationType = InvocationType.NON_BLOCKING;

        if (stalledEntry != null)
        {
            int size = pendingEntries.size();
            for (int i = 0; i < size; ++i)
            {
                Entry entry = pendingEntries.peek();
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
        lease.recycle();

        Throwable closed;
        Set<Entry> allEntries;
        try (AutoLock l = lock.lock())
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

    void terminate(Throwable cause)
    {
        Throwable closed;
        try (AutoLock l = lock.lock())
        {
            closed = terminated;
            terminated = cause;
            if (LOG.isDebugEnabled())
                LOG.debug("{} {}", closed != null ? "Terminated" : "Terminating", this);
        }
        if (closed == null)
            iterate();
    }

    private void closed(Entry entry, Throwable failure)
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

    public abstract static class Entry extends Callback.Nested
    {
        protected final Frame frame;
        protected final IStream stream;

        protected Entry(Frame frame, IStream stream, Callback callback)
        {
            super(callback);
            this.frame = frame;
            this.stream = stream;
        }

        public abstract int getFrameBytesGenerated();

        public int getDataBytesRemaining()
        {
            return 0;
        }

        protected abstract boolean generate(ByteBufferPool.Lease lease) throws HpackException;

        public abstract long onFlushed(long bytes) throws IOException;

        boolean hasHighPriority()
        {
            return false;
        }

        @Override
        public void failed(Throwable x)
        {
            if (stream != null)
            {
                stream.close();
                stream.getSession().removeStream(stream);
            }
            super.failed(x);
        }

        /**
         * @return whether the entry should not be processed
         */
        private boolean shouldBeDropped()
        {
            switch (frame.getType())
            {
                // Frames of this type should not be dropped.
                case PRIORITY:
                case SETTINGS:
                case PING:
                case GO_AWAY:
                case WINDOW_UPDATE:
                case PREFACE:
                case DISCONNECT:
                    return false;
                // Frames of this type follow the logic below.
                case DATA:
                case HEADERS:
                case PUSH_PROMISE:
                case CONTINUATION:
                case RST_STREAM:
                    break;
                default:
                    throw new IllegalStateException();
            }

            // SPEC: section 6.4.
            if (frame.getType() == FrameType.RST_STREAM)
                return stream != null && stream.isLocal() && !stream.isCommitted();

            // Frames that do not have a stream associated are dropped.
            if (stream == null)
                return true;

            return stream.isResetOrFailed();
        }

        void commit()
        {
            if (stream != null)
                stream.commit();
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }

    private class WindowEntry
    {
        private final IStream stream;
        private final WindowUpdateFrame frame;

        public WindowEntry(IStream stream, WindowUpdateFrame frame)
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
