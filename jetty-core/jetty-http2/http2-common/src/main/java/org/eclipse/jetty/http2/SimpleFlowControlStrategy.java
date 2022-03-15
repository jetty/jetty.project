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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleFlowControlStrategy extends AbstractFlowControlStrategy
{
    private static final Logger LOG = LoggerFactory.getLogger(SimpleFlowControlStrategy.class);

    public SimpleFlowControlStrategy()
    {
        this(DEFAULT_WINDOW_SIZE);
    }

    public SimpleFlowControlStrategy(int initialStreamSendWindow)
    {
        super(initialStreamSendWindow);
    }

    @Override
    public void onDataConsumed(ISession session, IStream stream, int length)
    {
        if (length <= 0)
            return;

        // This is the simple algorithm for flow control.
        // This method is called when a whole flow controlled frame has been consumed.
        // We send a WindowUpdate every time, even if the frame was very small.

        List<Frame> frames = new ArrayList<>(2);
        WindowUpdateFrame sessionFrame = new WindowUpdateFrame(0, length);
        frames.add(sessionFrame);
        session.updateRecvWindow(length);
        if (LOG.isDebugEnabled())
            LOG.debug("Data consumed, increased session recv window by {} for {}", length, session);

        if (stream != null)
        {
            if (stream.isRemotelyClosed())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Data consumed, ignoring update stream recv window by {} for remotely closed {}", length, stream);
            }
            else
            {
                WindowUpdateFrame streamFrame = new WindowUpdateFrame(stream.getId(), length);
                frames.add(streamFrame);
                stream.updateRecvWindow(length);
                if (LOG.isDebugEnabled())
                    LOG.debug("Data consumed, increased stream recv window by {} for {}", length, stream);
            }
        }

        session.frames(stream, frames, Callback.NOOP);
    }
}
