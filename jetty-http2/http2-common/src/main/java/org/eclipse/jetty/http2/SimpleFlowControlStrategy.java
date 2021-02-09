//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.util.Callback;

public class SimpleFlowControlStrategy extends AbstractFlowControlStrategy
{
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
