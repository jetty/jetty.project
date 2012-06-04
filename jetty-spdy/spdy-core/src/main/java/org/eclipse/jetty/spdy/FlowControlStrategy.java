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
