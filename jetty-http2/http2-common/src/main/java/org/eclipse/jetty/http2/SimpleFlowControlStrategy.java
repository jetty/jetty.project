//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2;

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

        final WindowUpdateFrame sessionFrame = new WindowUpdateFrame(0, length);
        session.updateRecvWindow(length);
        if (LOG.isDebugEnabled())
            LOG.debug("Data consumed, increased session recv window by {} for {}", length, session);

        Frame[] streamFrame = Frame.EMPTY_ARRAY;
        if (stream != null)
        {
            if (stream.isRemotelyClosed())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Data consumed, ignoring update stream recv window by {} for remotely closed {}", length, stream);
            }
            else
            {
                streamFrame = new Frame[1];
                streamFrame[0] = new WindowUpdateFrame(stream.getId(), length);
                stream.updateRecvWindow(length);
                if (LOG.isDebugEnabled())
                    LOG.debug("Data consumed, increased stream recv window by {} for {}", length, stream);
            }
        }

        session.frames(stream, Callback.NOOP, sessionFrame, streamFrame);
    }
}
