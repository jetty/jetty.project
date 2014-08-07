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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HTTP2FlowControl implements FlowControl
{
    private static final Logger LOG = Log.getLogger(HTTP2FlowControl.class);

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

        // Update the session's window size.
        session.onUpdateWindowSize(null, new WindowUpdateFrame(0, delta));

        // Update the streams' window size.
        for (Stream stream : session.getStreams())
            session.onUpdateWindowSize((IStream)stream, new WindowUpdateFrame(stream.getId(), delta));
    }

    @Override
    public void onWindowUpdate(ISession session, IStream stream, WindowUpdateFrame frame)
    {
        int delta = frame.getWindowDelta();
        if (frame.getStreamId() > 0)
        {
            // The stream may have been reset concurrently.
            if (stream != null)
            {
                int oldSize = stream.updateWindowSize(delta);
                if (LOG.isDebugEnabled())
                    LOG.debug("Updated stream window {} -> {} for {}", oldSize, oldSize + delta, stream);
            }
        }
        else
        {
            int oldSize = session.updateWindowSize(delta);
            if (LOG.isDebugEnabled())
                LOG.debug("Updated session window {} -> {} for {}", oldSize, oldSize + delta, session);
        }
    }

    @Override
    public void onDataReceived(IStream stream, int length)
    {
    }

    @Override
    public void onDataConsumed(IStream stream, int length)
    {
        // This is the algorithm for flow control.
        // This method is called when a whole flow controlled frame has been consumed.
        // We currently send a WindowUpdate every time, even if the frame was very small.
        // Other policies may send the WindowUpdate only upon reaching a threshold.

        if (LOG.isDebugEnabled())
            LOG.debug("Data consumed, increasing window by {} for {}", length, stream);

        if (length > 0)
        {
            // Negative streamId allow for generation of bytes for both stream and session
            WindowUpdateFrame frame = new WindowUpdateFrame(-stream.getId(), length);
            stream.getSession().control(stream, frame, Callback.Adapter.INSTANCE);
        }
    }

    @Override
    public void onDataSending(IStream stream, int length)
    {
        if (length == 0)
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("Data sending, decreasing windows by {}", length);

        ISession session = stream.getSession();
        int oldSize = session.updateWindowSize(-length);
        if (LOG.isDebugEnabled())
            LOG.debug("Updated session window {} -> {} for {}", oldSize, oldSize - length, session);

        oldSize = stream.updateWindowSize(-length);
        if (LOG.isDebugEnabled())
            LOG.debug("Updated stream window {} -> {} for {}", oldSize, oldSize - length, stream);
    }

    @Override
    public void onDataSent(IStream stream, int length)
    {
    }

    @Override
    public void onSessionStalled(ISession session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Session stalled {}", session);
    }

    @Override
    public void onStreamStalled(IStream stream)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Stream stalled {}", stream);
    }
}
