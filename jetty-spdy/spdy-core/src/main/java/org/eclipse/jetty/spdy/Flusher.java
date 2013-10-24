//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.spdy.api.SPDYException;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class Flusher
{
    private static final Logger LOG = Log.getLogger(Flusher.class);

    private final IteratingCallback iteratingCallback = new SessionIteratingCallback();
    private final Controller controller;
    private final LinkedList<StandardSession.FrameBytes> queue = new LinkedList<>();
    private Throwable failure;
    private StandardSession.FrameBytes active;

    private boolean flushing;

    public Flusher(Controller controller)
    {
        this.controller = controller;
    }

    void removeFrameBytesFromQueue(Stream stream)
    {
        synchronized (queue)
        {
            for (StandardSession.FrameBytes frameBytes : queue)
                if (frameBytes.getStream() == stream)
                    queue.remove(frameBytes);
        }
    }

    void append(StandardSession.FrameBytes frameBytes)
    {
        Throwable failure;
        synchronized (queue)
        {
            failure = this.failure;
            if (failure == null)
            {
                // Frames containing headers must be send in the order the headers have been generated. We don't need
                // to do this check in StandardSession.prepend() as no frames containing headers will be prepended.
                if (frameBytes instanceof StandardSession.ControlFrameBytes)
                    queue.addLast(frameBytes);
                else
                {
                    int index = queue.size();
                    while (index > 0)
                    {
                        StandardSession.FrameBytes element = queue.get(index - 1);
                        if (element.compareTo(frameBytes) >= 0)
                            break;
                        --index;
                    }
                    queue.add(index, frameBytes);
                }
            }
        }
        if (failure == null)
            iteratingCallback.iterate();
        else
            frameBytes.failed(new SPDYException(failure));
    }

    void prepend(StandardSession.FrameBytes frameBytes)
    {
        Throwable failure;
        synchronized (queue)
        {
            failure = this.failure;
            if (failure == null)
            {
                int index = 0;
                while (index < queue.size())
                {
                    StandardSession.FrameBytes element = queue.get(index);
                    if (element.compareTo(frameBytes) <= 0)
                        break;
                    ++index;
                }
                queue.add(index, frameBytes);
            }
        }

        if (failure == null)
            iteratingCallback.iterate();
        else
            frameBytes.failed(new SPDYException(failure));
    }

    void flush()
    {
        StandardSession.FrameBytes frameBytes = null;
        ByteBuffer buffer = null;
        boolean failFrameBytes = false;
        synchronized (queue)
        {
            if (flushing || queue.isEmpty())
                return;

            Set<IStream> stalledStreams = null;
            for (int i = 0; i < queue.size(); ++i)
            {
                frameBytes = queue.get(i);

                IStream stream = frameBytes.getStream();
                if (stream != null && stalledStreams != null && stalledStreams.contains(stream))
                    continue;

                buffer = frameBytes.getByteBuffer();
                if (buffer != null)
                {
                    queue.remove(i);
                    if (stream != null && stream.isReset() && !(frameBytes instanceof StandardSession
                            .ControlFrameBytes))
                        failFrameBytes = true;
                    break;
                }

                if (stalledStreams == null)
                    stalledStreams = new HashSet<>();
                if (stream != null)
                    stalledStreams.add(stream);

                LOG.debug("Flush stalled for {}, {} frame(s) in queue", frameBytes, queue.size());
            }

            if (buffer == null)
                return;

            if (!failFrameBytes)
            {
                flushing = true;
                LOG.debug("Flushing {}, {} frame(s) in queue", frameBytes, queue.size());
            }
        }
        if (failFrameBytes)
        {
            frameBytes.failed(new StreamException(frameBytes.getStream().getId(), StreamStatus.INVALID_STREAM,
                    "Stream: " + frameBytes.getStream() + " is reset!"));
        }
        else
        {
            write(buffer, frameBytes);
        }
    }

    private void write(ByteBuffer buffer, StandardSession.FrameBytes frameBytes)
    {
        active = frameBytes;
        if (controller != null)
        {
            LOG.debug("Writing {} frame bytes of {}", buffer.remaining(), buffer.limit());
            controller.write(buffer, iteratingCallback);
        }
    }

    public int getQueueSize()
    {
        return queue.size();
    }

    private class SessionIteratingCallback extends IteratingCallback
    {
        @Override
        protected boolean process() throws Exception
        {
            flush();
            return false;
        }

        @Override
        protected void completed()
        {
            // will never be called as process always returns false!
        }

        @Override
        public void succeeded()
        {
            if (LOG.isDebugEnabled())
            {
                synchronized (queue)
                {
                    LOG.debug("Completed write of {}, {} frame(s) in queue", active, queue.size());
                }
            }
            active.succeeded();
            synchronized (queue)
            {
                flushing = false;
            }
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            List<StandardSession.FrameBytes> frameBytesToFail = new ArrayList<>();

            synchronized (queue)
            {
                failure = x;
                if (LOG.isDebugEnabled())
                {
                    String logMessage = String.format("Failed write of %s, failing all %d frame(s) in queue", this, queue.size());
                    LOG.debug(logMessage, x);
                }
                frameBytesToFail.addAll(queue);
                queue.clear();
            }

            active.failed(x);
            for (StandardSession.FrameBytes fb : frameBytesToFail)
                fb.failed(x);
            super.failed(x);
        }
    }

}
