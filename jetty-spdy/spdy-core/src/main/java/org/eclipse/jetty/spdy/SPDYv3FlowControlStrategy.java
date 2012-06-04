/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.frames.WindowUpdateFrame;

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
            session.control(stream, windowUpdateFrame, 0, TimeUnit.MILLISECONDS, null, null);
        }
    }
}
