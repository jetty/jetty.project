//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HTTP2Flusher extends IteratingCallback
{
    private static final Logger LOG = Log.getLogger(HTTP2Flusher.class);

    private final Deque<WindowEntry> windows = new ArrayDeque<>();
    private final ArrayQueue<Entry> frames = new ArrayQueue<>(ArrayQueue.DEFAULT_CAPACITY, ArrayQueue.DEFAULT_GROWTH, this);
    private final Map<IStream, Integer> streams = new HashMap<>();
    private final List<Entry> reset = new ArrayList<>();
    private final HTTP2Session session;
    private final ByteBufferPool.Lease lease;
    private final List<Entry> active;
    private final Queue<Entry> complete;

    public HTTP2Flusher(HTTP2Session session)
    {
        this.session = session;
        this.lease = new ByteBufferPool.Lease(session.getGenerator().getByteBufferPool());
        this.active = new ArrayList<>();
        this.complete = new ArrayDeque<>();
    }

    public void window(IStream stream, WindowUpdateFrame frame)
    {
        synchronized (this)
        {
            if (!isClosed())
            {
                windows.offer(new WindowEntry(stream, frame));
                // Flush stalled data.
                iterate();
            }
        }
    }

    public void prepend(Entry entry)
    {
        boolean fail = false;
        synchronized (this)
        {
            if (isClosed())
            {
                fail = true;
            }
            else
            {
                frames.add(0, entry);
                if (LOG.isDebugEnabled())
                    LOG.debug("Prepended {}, frames={}", entry, frames.size());
            }
        }
        if (fail)
            closed(entry, new ClosedChannelException());
    }

    public void append(Entry entry)
    {
        boolean fail = false;
        synchronized (this)
        {
            if (isClosed())
            {
                fail = true;
            }
            else
            {
                frames.offer(entry);
                if (LOG.isDebugEnabled())
                    LOG.debug("Appended {}, frames={}", entry, frames.size());
            }
        }
        if (fail)
            closed(entry, new ClosedChannelException());
    }

    public int getQueueSize()
    {
        synchronized (this)
        {
            return frames.size();
        }
    }

    @Override
    protected Action process() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Flushing {}", session);

        synchronized (this)
        {
            // First thing, update the window sizes, so we can
            // reason about the frames to remove from the queue.
            while (!windows.isEmpty())
            {
                WindowEntry entry = windows.poll();
                entry.perform();
            }

            // Now the window sizes cannot change.
            // Window updates that happen concurrently will
            // be queued and processed on the next iteration.
            int sessionWindow = session.getSendWindow();

            int index = 0;
            int size = frames.size();
            while (index < size)
            {
                Entry entry = frames.get(index);

                // We need to compute how many frames fit in the windows.

                IStream stream = entry.stream;
                int remaining = entry.dataRemaining();
                if (remaining > 0)
                {
                    if (sessionWindow <= 0)
                    {
                        session.getFlowControl().onSessionStalled(session);
                        ++index;
                        // There may be *non* flow controlled frames to send.
                        continue;
                    }

                    // The stream may have a smaller window than the session.
                    Integer streamWindow = streams.get(stream);
                    if (streamWindow == null)
                    {
                        streamWindow = stream.getSendWindow();
                        streams.put(stream, streamWindow);
                    }

                    // Is it a frame belonging to an already stalled stream ?
                    if (streamWindow <= 0)
                    {
                        session.getFlowControl().onStreamStalled(stream);
                        ++index;
                        // There may be *non* flow controlled frames to send.
                        continue;
                    }
                }

                // We will be possibly writing this
                // frame, remove it from the queue.
                if (index == 0)
                    frames.pollUnsafe();
                else
                    frames.remove(index);
                --size;

                // If the stream has been reset, don't send the frame.
                if (stream != null && stream.isReset())
                {
                    reset.add(entry);
                    continue;
                }

                // Reduce the flow control windows.
                if (remaining > 0)
                {
                    sessionWindow -= remaining;
                    streams.put(stream, streams.get(stream) - remaining);
                }

                // The frame will be written.
                active.add(entry);

                if (LOG.isDebugEnabled())
                    LOG.debug("Gathered {}", entry);
            }
            streams.clear();
        }

        // Perform resets outside the sync block.
        for (int i = 0; i < reset.size(); ++i)
        {
            Entry entry = reset.get(i);
            entry.reset();
        }
        reset.clear();

        if (active.isEmpty())
        {
            if (isClosed())
                terminate(new ClosedChannelException());

            if (LOG.isDebugEnabled())
                LOG.debug("Flushed {}", session);

            return Action.IDLE;
        }

        for (int i = 0; i < active.size(); ++i)
        {
            Entry entry = active.get(i);
            Throwable failure = entry.generate(lease);
            if (failure != null)
            {
                // Failure to generate the entry is catastrophic.
                failed(failure);
                return Action.SUCCEEDED;
            }
        }

        List<ByteBuffer> byteBuffers = lease.getByteBuffers();
        if (LOG.isDebugEnabled())
            LOG.debug("Writing {} buffers ({} bytes) for {} frames {}", byteBuffers.size(), lease.getTotalLength(), active.size(), active);
        session.getEndPoint().write(this, byteBuffers.toArray(new ByteBuffer[byteBuffers.size()]));
        return Action.SCHEDULED;
    }

    @Override
    public void succeeded()
    {
        lease.recycle();

        // Transfer active items to avoid reentrancy.
        for (int i = 0; i < active.size(); ++i)
            complete.add(active.get(i));
        active.clear();

        if (LOG.isDebugEnabled())
            LOG.debug("Written {} frames for {}", complete.size(), complete);

        // Drain the frames one by one to avoid reentrancy.
        while (!complete.isEmpty())
        {
            Entry entry = complete.poll();
            entry.succeeded();
        }

        super.succeeded();
    }

    @Override
    protected void onCompleteSuccess()
    {
        throw new IllegalStateException();
    }

    @Override
    protected void onCompleteFailure(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug(x);

        lease.recycle();

        // Transfer active items to avoid reentrancy.
        for (int i = 0; i < active.size(); ++i)
            complete.add(active.get(i));
        active.clear();

        // Drain the frames one by one to avoid reentrancy.
        while (!complete.isEmpty())
        {
            Entry entry = complete.poll();
            entry.failed(x);
        }

        terminate(x);
    }

    private void terminate(Throwable x)
    {
        Queue<Entry> queued;
        synchronized (this)
        {
            queued = new ArrayDeque<>(frames);
            frames.clear();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Terminating, queued={}", queued.size());

        for (Entry entry : queued)
            closed(entry, x);

        session.disconnect();
    }

    private void closed(Entry entry, Throwable failure)
    {
        entry.failed(failure);
    }

    public static abstract class Entry implements Callback
    {
        protected final Frame frame;
        protected final IStream stream;
        protected final Callback callback;

        protected Entry(Frame frame, IStream stream, Callback callback)
        {
            this.frame = frame;
            this.stream = stream;
            this.callback = callback;
        }

        public int dataRemaining()
        {
            return 0;
        }

        public Throwable generate(ByteBufferPool.Lease lease)
        {
            return null;
        }

        public void reset()
        {
            failed(new EOFException("reset"));
        }

        @Override
        public void failed(Throwable x)
        {
            callback.failed(x);
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
            FlowControl flowControl = session.getFlowControl();
            flowControl.onWindowUpdate(session, stream, frame);
        }
    }
}
