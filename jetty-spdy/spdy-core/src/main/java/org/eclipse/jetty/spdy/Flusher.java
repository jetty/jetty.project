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

package org.eclipse.jetty.spdy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.spdy.StandardSession.FrameBytes;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class Flusher
{
    private static final Logger LOG = Log.getLogger(Flusher.class);

    private final IteratingCallback callback = new FlusherCallback();
    private final Object lock = new Object();
    private final ArrayQueue<StandardSession.FrameBytes> queue = new ArrayQueue<>(ArrayQueue.DEFAULT_CAPACITY, ArrayQueue.DEFAULT_GROWTH, lock);
    private final Controller controller;
    private final int maxGather;
    private Throwable failure;

    public Flusher(Controller controller)
    {
        this(controller, 8);
    }

    public Flusher(Controller controller, int maxGather)
    {
        this.controller = controller;
        this.maxGather = maxGather;
    }

    public void removeFrameBytesFromQueue(Stream stream)
    {
        synchronized (lock)
        {
            for (StandardSession.FrameBytes frameBytes : queue)
                if (frameBytes.getStream() == stream)
                    queue.remove(frameBytes);
        }
    }

    public Throwable prepend(StandardSession.FrameBytes frameBytes)
    {
        synchronized (lock)
        {
            Throwable failure = this.failure;
            if (failure == null)
            {
                // Scan from the front of the queue looking to skip higher priority messages
                int index = 0;
                int size = queue.size();
                while (index < size)
                {
                    StandardSession.FrameBytes element = queue.getUnsafe(index);
                    if (element.compareTo(frameBytes) <= 0)
                        break;
                    ++index;
                }
                queue.add(index, frameBytes);
            }
            return failure;
        }
    }

    public Throwable append(StandardSession.FrameBytes frameBytes)
    {
        synchronized (lock)
        {
            Throwable failure = this.failure;
            if (failure == null)
            {
                // Non DataFrameBytes are inserted last
                queue.add(frameBytes);
            }
            return failure;
        }
    }

    public Throwable append(StandardSession.DataFrameBytes frameBytes)
    {
        synchronized (lock)
        {
            Throwable failure = this.failure;
            if (failure == null)
            {
                // DataFrameBytes are inserted by priority
                int index = queue.size();
                while (index > 0)
                {
                    StandardSession.FrameBytes element = queue.getUnsafe(index - 1);
                    if (element.compareTo(frameBytes) >= 0)
                        break;
                    --index;
                }
                queue.add(index, frameBytes);
            }
            return failure;
        }
    }

    public void flush()
    {
        callback.iterate();
    }

    public int getQueueSize()
    {
        synchronized (lock)
        {
            return queue.size();
        }
    }

    private class FlusherCallback extends IteratingCallback
    {
        private final List<StandardSession.FrameBytes> active = new ArrayList<>(maxGather);
        private final List<StandardSession.FrameBytes> succeeded = new ArrayList<>(maxGather);
        private final Set<IStream> stalled = new HashSet<>();

        @Override
        protected Action process() throws Exception
        {
            synchronized (lock)
            {
                // Scan queue for data to write from first non stalled stream.
                int index = 0; // The index of the first non-stalled frame.
                int size = queue.size();
                while (index < size)
                {
                    FrameBytes frameBytes = queue.getUnsafe(index);
                    IStream stream = frameBytes.getStream();

                    if (stream != null)
                    {
                        // Is it a frame belonging to an already stalled stream ?
                        if (stalled.size() > 0 && stalled.contains(stream))
                        {
                            ++index;
                            continue;
                        }

                        // Has the stream consumed all its flow control window ?
                        if (stream.getWindowSize() <= 0)
                        {
                            stalled.add(stream);
                            ++index;
                            continue;
                        }
                    }

                    // We will be possibly writing this frame, so take the frame off the queue.
                    queue.remove(index);
                    --size;

                    // Has the stream been reset for this data frame ?
                    if (stream != null && stream.isReset() && frameBytes instanceof StandardSession.DataFrameBytes)
                    {
                        // TODO: notify from within sync block !
                        frameBytes.failed(new StreamException(frameBytes.getStream().getId(),
                                StreamStatus.INVALID_STREAM, "Stream: " + frameBytes.getStream() + " is reset!"));
                        continue;
                    }

                    active.add(frameBytes);
                }
                stalled.clear();

                if (LOG.isDebugEnabled())
                    LOG.debug("Flushing {} of {} frame(s) in queue", active.size(), queue.size());
            }

            if (active.isEmpty())
                return Action.IDLE;

            // Get the bytes to write
            ByteBuffer[] buffers = new ByteBuffer[active.size()];
            for (int i = 0; i < buffers.length; i++)
                buffers[i] = active.get(i).getByteBuffer();

            if (controller != null)
                controller.write(this, buffers);

            // TODO: optimization
            // If the callback completely immediately, it means that
            // the connection is not congested, and therefore we can
            // write more data without blocking.
            // Therefore we should check this condition and increase
            // the write window, which means two things: autotune the
            // maxGather parameter, and/or autotune the buffer returned
            // by FrameBytes.getByteBuffer() (see also comment there).

            return Action.SCHEDULED;
        }

        @Override
        protected void completed()
        {
            // will never be called as process always returns SCHEDULED or IDLE
            throw new IllegalStateException();
        }

        @Override
        public void succeeded()
        {
            synchronized (lock)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Succeeded write of {}, q={}", active, queue.size());
                succeeded.addAll(active);
                active.clear();
            }
            // Notify outside the synchronized block.
            for (FrameBytes frame : succeeded)
                frame.succeeded();
            succeeded.clear();
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            List<StandardSession.FrameBytes> failed = new ArrayList<>();
            synchronized (lock)
            {
                failure = x;
                if (LOG.isDebugEnabled())
                {
                    String logMessage = String.format("Failed write of %s, failing all %d frame(s) in queue", this, queue.size());
                    LOG.debug(logMessage, x);
                }
                failed.addAll(active);
                active.clear();
                failed.addAll(queue);
                queue.clear();
            }
            // Notify outside the synchronized block.
            for (StandardSession.FrameBytes frame : failed)
                frame.failed(x);
            super.failed(x);
        }
    }
}
