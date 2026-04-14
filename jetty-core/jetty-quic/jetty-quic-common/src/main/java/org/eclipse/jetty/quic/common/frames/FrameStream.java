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

package org.eclipse.jetty.quic.common.frames;

import java.util.PriorityQueue;
import java.util.Queue;
import java.util.function.Consumer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// A stream of frames that carry data bytes that
/// must be delivered to applications in order.
///
/// CRYPTO and STREAM frames may arrive out-of-order,
/// within QUIC packets, and this class is responsible
/// for reordering them before delivering them to the
/// application via the provided listener.
public class FrameStream
{
    private static final Logger LOG = LoggerFactory.getLogger(FrameStream.class);

    private final Queue<Frame.WithData> frames = new PriorityQueue<>();
    private final Consumer<Frame.WithData> listener;
    private long offset;

    public FrameStream(Consumer<Frame.WithData> listener)
    {
        this.listener = listener;
    }

    public void offer(Frame.WithData frame)
    {
        // Retain because it is stored for later use.
        // When the frame is removed from the queue,
        // it will be closed and therefore released.
        frame.accept(RetainableByteBuffer::retain);
        frames.offer(frame);

        if (LOG.isDebugEnabled())
            LOG.debug("offered {} on {}", frame, this);

        while (true)
        {
            Frame.WithData candidate = frames.peek();
            if (candidate == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("stalling, no data on {}", this);
                return;
            }

            long offsetEnd = candidate.offset() + candidate.length();
            if (offsetEnd <= offset)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("discarding, data delivered {} on {}", candidate, this);
                frames.poll();
                candidate.close();
                continue;
            }

            if (candidate.offset() < offset)
            {
                long length = offsetEnd - offset;
                try (Frame.WithData newFrame = candidate.slice(offset, length))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("notifying, data slice {} on {}", newFrame, this);
                    frames.poll();
                    candidate.close();
                    offset += length;
                    notifyFrame(newFrame);
                }
            }
            else if (candidate.offset() == offset)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("notifying, data {} on {}", candidate, this);
                frames.poll();
                offset += candidate.length();
                notifyFrame(candidate);
                candidate.close();
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("stalling, data gap on {}", this);
                return;
            }
        }
    }

    long offset()
    {
        return offset;
    }

    private void notifyFrame(Frame.WithData frame)
    {
        try
        {
            listener.accept(frame);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    @Override
    public String toString()
    {
        return "%s@%x[offset=%d,queue=%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), offset, frames.size());
    }
}
