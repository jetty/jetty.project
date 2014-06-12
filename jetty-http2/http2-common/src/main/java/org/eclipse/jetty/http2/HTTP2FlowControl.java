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

import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.util.Callback;

public class HTTP2FlowControl implements FlowControl
{
    private volatile int initialWindowSize;

    public HTTP2FlowControl(int initialWindowSize)
    {
        this.initialWindowSize = initialWindowSize;
    }

    @Override
    public void onNewStream(IStream stream)
    {
        stream.updateWindowSize(initialWindowSize);
    }

    @Override
    public int getInitialWindowSize()
    {
        return initialWindowSize;
    }

    @Override
    public void updateInitialWindowSize(ISession session, int initialWindowSize)
    {
        int windowSize = this.initialWindowSize;
        this.initialWindowSize = initialWindowSize;

        int delta = initialWindowSize - windowSize;

        // Update the sessions's window size.
        session.updateWindowSize(delta);

        // Update the streams' window size.
        for (Stream stream : session.getStreams())
            ((IStream)stream).updateWindowSize(delta);
    }

    @Override
    public void onWindowUpdate(ISession session, IStream stream, WindowUpdateFrame frame)
    {
        if (frame.getStreamId() > 0)
        {
            if (stream != null)
                stream.updateWindowSize(frame.getWindowDelta());
        }
        else
        {
            session.updateWindowSize(frame.getWindowDelta());
        }
    }

    @Override
    public void onDataReceived(ISession session, IStream stream, int length)
    {
    }

    @Override
    public void onDataConsumed(ISession session, IStream stream, int length)
    {
        // This is the algorithm for flow control.
        // This method is called when a whole flow controlled frame has been consumed.
        // We currently send a WindowUpdate every time, even if the frame was very small.
        // Other policies may send the WindowUpdate only upon reaching a threshold.

        // Negative streamId allow for generation of bytes for both stream and session
        int streamId = stream != null ? -stream.getId() : 0;
        WindowUpdateFrame frame = new WindowUpdateFrame(streamId, length);
        session.frame(stream, frame, Callback.Adapter.INSTANCE);
    }

    @Override
    public void onDataSent(ISession session, IStream stream, int length)
    {
        stream.getSession().updateWindowSize(length);
        stream.updateWindowSize(length);
    }
}
