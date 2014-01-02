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

import org.eclipse.jetty.spdy.api.DataInfo;

// TODO: add methods that tell how much written and whether we're TCP congested ?
public interface FlowControlStrategy
{
    public int getWindowSize(ISession session);

    public void setWindowSize(ISession session, int windowSize);

    public void onNewStream(ISession session, IStream stream);

    public void onWindowUpdate(ISession session, IStream stream, int delta);

    public void updateWindow(ISession session, IStream stream, int delta);

    public void onDataReceived(ISession session, IStream stream, DataInfo dataInfo);

    public void onDataConsumed(ISession session, IStream stream, DataInfo dataInfo, int delta);

    public static class None implements FlowControlStrategy
    {
        private volatile int windowSize;

        public None()
        {
            this(65536);
        }

        public None(int windowSize)
        {
            this.windowSize = windowSize;
        }

        @Override
        public int getWindowSize(ISession session)
        {
            return windowSize;
        }

        @Override
        public void setWindowSize(ISession session, int windowSize)
        {
            this.windowSize = windowSize;
        }

        @Override
        public void onNewStream(ISession session, IStream stream)
        {
            stream.updateWindowSize(windowSize);
        }

        @Override
        public void onWindowUpdate(ISession session, IStream stream, int delta)
        {
        }

        @Override
        public void updateWindow(ISession session, IStream stream, int delta)
        {
        }

        @Override
        public void onDataReceived(ISession session, IStream stream, DataInfo dataInfo)
        {
        }

        @Override
        public void onDataConsumed(ISession session, IStream stream, DataInfo dataInfo, int delta)
        {
        }
    }
}
