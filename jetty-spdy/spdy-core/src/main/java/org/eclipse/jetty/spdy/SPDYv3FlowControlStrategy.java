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

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.frames.WindowUpdateFrame;
import org.eclipse.jetty.util.Callback;

public class SPDYv3FlowControlStrategy implements FlowControlStrategy
{
    private volatile int windowSize;

    @Override
    public int getWindowSize(ISession session)
    {
        return windowSize;
    }

    @Override
    public void setWindowSize(ISession session, int windowSize)
    {
        int prevWindowSize = this.windowSize;
        this.windowSize = windowSize;
        for (Stream stream : session.getStreams())
            ((IStream)stream).updateWindowSize(windowSize - prevWindowSize);
    }

    @Override
    public void onNewStream(ISession session, IStream stream)
    {
        stream.updateWindowSize(windowSize);
    }

    @Override
    public void onWindowUpdate(ISession session, IStream stream, int delta)
    {
        if (stream != null)
            stream.updateWindowSize(delta);
    }

    @Override
    public void updateWindow(ISession session, IStream stream, int delta)
    {
        stream.updateWindowSize(delta);
    }

    @Override
    public void onDataReceived(ISession session, IStream stream, DataInfo dataInfo)
    {
        // Do nothing
    }

    @Override
    public void onDataConsumed(ISession session, IStream stream, DataInfo dataInfo, int delta)
    {
        // This is the algorithm for flow control.
        // This method may be called multiple times with delta=1, but we only send a window
        // update when the whole dataInfo has been consumed.
        // Other policies may be to send window updates when consumed() is greater than
        // a certain threshold, etc. but for now the policy is not pluggable for simplicity.
        // Note that the frequency of window updates depends on the read buffer, that
        // should not be too smaller than the window size to avoid frequent window updates.
        // Therefore, a pluggable policy should be able to modify the read buffer capacity.
        int length = dataInfo.length();
        if (dataInfo.consumed() == length && !stream.isClosed() && length > 0)
        {
            WindowUpdateFrame windowUpdateFrame = new WindowUpdateFrame(session.getVersion(), stream.getId(), length);
            session.control(stream, windowUpdateFrame, 0, TimeUnit.MILLISECONDS, Callback.Adapter.INSTANCE);
        }
    }
}
