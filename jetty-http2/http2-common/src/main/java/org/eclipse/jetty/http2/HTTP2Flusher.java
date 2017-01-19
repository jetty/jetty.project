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

package org.eclipse.jetty.http2;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HTTP2Flusher extends IteratingCallback
{
    private static final Logger LOG = Log.getLogger(HTTP2Flusher.class);

    private final Queue<WindowEntry> windows = new ArrayDeque<>();
    private final List<Entry> frames = new ArrayList<>();
    private final Map<IStream, Integer> streams = new HashMap<>();
    private final List<Entry> resets = new ArrayList<>();
    private final List<Entry> actives = new ArrayList<>();
    private final HTTP2Session session;
    private final ByteBufferPool.Lease lease;
    private Throwable terminated;

    public HTTP2Flusher(HTTP2Session session)
    {
        this.session = session;
        this.lease = new ByteBufferPool.Lease(session.getGenerator().getByteBufferPool());
    }

    public void window(IStream stream, WindowUpdateFrame frame)
    {
        Throwable closed;
        synchronized (this)
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
        synchronized (this)
        {
            closed = terminated;
            if (closed == null)
            {
                frames.add(0, entry);
                if (LOG.isDebugEnabled())
                    LOG.debug("Prepended {}, frames={}", entry, frames.size());
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
        synchronized (this)
        {
            closed = terminated;
            if (closed == null)
            {
                frames.add(entry);
                if (LOG.isDebugEnabled())
                    LOG.debug("Appended {}, frames={}", entry, frames.size());
            }
        }
        if (closed == null)
            return true;
        closed(entry, closed);
        return false;
    }

    private Entry remove(int index)
    {
        synchronized (this)
        {
            return frames.remove(index);
        }
    }

    public int getQueueSize()
    {
        synchronized (this)
        {
            return frames.size();
        }
    }

    @Override
    protected Action process() throws Throwable
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Flushing {}", session);

        synchronized (this)
        {
            if (terminated != null)
                throw terminated;

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
                IStream stream = entry.stream;

                // If the stream has been reset, don't send the frame.
                if (stream != null && stream.isReset() && !entry.isProtocol())
                {
                    remove(index);
                    --size;
                    resets.add(entry);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Gathered for reset {}", entry);
                    continue;
                }

                // Check if the frame fits in the flow control windows.
                int remaining = entry.dataRemaining();
                if (remaining > 0)
                {
                    if (sessionWindow <= 0)
                    {
                        ++index;
                        // There may be *non* flow controlled frames to send.
                        continue;
                    }

                    if (stream != null)
                    {
                        // The stream may have a smaller window than the session.
                        Integer streamWindow = streams.get(stream);
                        if (streamWindow == null)
                        {
                            streamWindow = stream.updateSendWindow(0);
                            streams.put(stream, streamWindow);
                        }

                        // Is it a frame belonging to an already stalled stream ?
                        if (streamWindow <= 0)
                        {
                            ++index;
                            // There may be *non* flow controlled frames to send.
                            continue;
                        }
                    }

                    // The frame fits both flow control windows, reduce them.
                    sessionWindow -= remaining;
                    if (stream != null)
                        streams.put(stream, streams.get(stream) - remaining);
                }

                // The frame will be written, remove it from the queue.
                remove(index);
                --size;
                actives.add(entry);

                if (LOG.isDebugEnabled())
                    LOG.debug("Gathered for write {}", entry);
            }
            streams.clear();
        }

        // Perform resets outside the sync block.
        for (int i = 0; i < resets.size(); ++i)
        {
            Entry entry = resets.get(i);
            entry.reset();
        }
        resets.clear();

        if (actives.isEmpty())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Flushed {}", session);
            return Action.IDLE;
        }

        for (int i = 0; i < actives.size(); ++i)
        {
            Entry entry = actives.get(i);
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
            LOG.debug("Writing {} buffers ({} bytes) for {} frames {}", byteBuffers.size(), lease.getTotalLength(), actives.size(), actives);
        session.getEndPoint().write(this, byteBuffers.toArray(new ByteBuffer[byteBuffers.size()]));
        return Action.SCHEDULED;
    }

    @Override
    public void succeeded()
    {
        lease.recycle();

        if (LOG.isDebugEnabled())
            LOG.debug("Written {} frames for {}", actives.size(), actives);

        actives.forEach(Entry::succeeded);
        actives.clear();

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
        lease.recycle();

        Throwable closed;
        synchronized (this)
        {
            closed = terminated;
            terminated = x;
            if (LOG.isDebugEnabled())
                LOG.debug("{}, active/queued={}/{}", closed != null ? "Closing" : "Failing", actives.size(), frames.size());
            actives.addAll(frames);
            frames.clear();
        }

        actives.forEach(entry -> entry.failed(x));
        actives.clear();

        // If the failure came from within the
        // flusher, we need to close the connection.
        if (closed == null)
            session.abort(x);
    }

    void terminate(Throwable cause)
    {
        Throwable closed;
        synchronized (this)
        {
            closed = terminated;
            terminated = cause;
            if (LOG.isDebugEnabled())
                LOG.debug("{}", closed != null ? "Terminated" : "Terminating");
        }
        if (closed == null)
            iterate();
    }

    private void closed(Entry entry, Throwable failure)
    {
        entry.failed(failure);
    }

    public static abstract class Entry extends Callback.Nested
    {
        protected final Frame frame;
        protected final IStream stream;

        protected Entry(Frame frame, IStream stream, Callback callback)
        {
            super(callback);
            this.frame = frame;
            this.stream = stream;
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
            failed(new EofException("reset"));
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

        public boolean isProtocol()
        {
            switch (frame.getType())
            {
                case PRIORITY:
                case RST_STREAM:
                case GO_AWAY:
                case WINDOW_UPDATE:
                case DISCONNECT:
                    return true;
                default:
                    return false;
            }
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
