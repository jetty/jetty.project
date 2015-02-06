//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.util.Callback;

public class SimpleFlowControlStrategy extends AbstractFlowControlStrategy
{
    public SimpleFlowControlStrategy()
    {
        this(DEFAULT_WINDOW_SIZE);
    }

    public SimpleFlowControlStrategy(int initialStreamWindow)
    {
        super(initialStreamWindow);
    }

    @Override
    public void onDataConsumed(ISession session, IStream stream, int length)
    {
        // This is the simple algorithm for flow control.
        // This method is called when a whole flow controlled frame has been consumed.
        // We send a WindowUpdate every time, even if the frame was very small.

        if (length > 0)
        {
            WindowUpdateFrame sessionFrame = new WindowUpdateFrame(0, length);
            session.updateRecvWindow(length);
            if (LOG.isDebugEnabled())
                LOG.debug("Data consumed, increased session recv window by {} for {}", length, session);

            Frame[] streamFrame = null;
            if (stream != null)
            {
                streamFrame = new Frame[1];
                streamFrame[0] = new WindowUpdateFrame(stream.getId(), length);
                stream.updateRecvWindow(length);
                if (LOG.isDebugEnabled())
                    LOG.debug("Data consumed, increased stream recv window by {} for {}", length, stream);
            }

            session.control(stream, Callback.Adapter.INSTANCE, sessionFrame, streamFrame == null ? Frame.EMPTY_ARRAY : streamFrame);
        }
    }
}
