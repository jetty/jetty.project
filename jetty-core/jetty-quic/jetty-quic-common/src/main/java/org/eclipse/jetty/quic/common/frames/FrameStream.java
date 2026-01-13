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

import org.eclipse.jetty.quic.api.frames.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// A stream of frames that carry data bytes that
/// must be delivered to applications in order.
///
/// CRYPTO and STREAM frames may arrive out-of-order,
/// within QUIC packets, and this class is responsible
/// for reordering them before delivering them to the
/// application via the [Frame.Listener].
public class FrameStream
{
    private static final Logger LOG = LoggerFactory.getLogger(FrameStream.class);

    private final Queue<Frame.WithOffset> queue = new PriorityQueue<>();
    private final Frame.Listener listener;
    private long offset;

    public FrameStream(Frame.Listener listener)
    {
        this.listener = listener;
    }

    public void offer(Frame.WithOffset frame)
    {
        queue.offer(frame);
        while (true)
        {
            Frame.WithOffset candidate = queue.peek();
            if (candidate == null)
                break;

            if (offset != candidate.offset())
                break;

            // This frame is in order, deliver it.
            queue.poll();
            notifyFrame(candidate);
            offset += candidate.length();
        }
    }

    private void notifyFrame(Frame.WithOffset frame)
    {
        try
        {
            listener.onFrame((Frame)frame);
        }
        catch (Throwable x)
        {
            LOG.atInfo().setCause(x).log("failure while notifying listener {}", listener);
        }
    }
}
