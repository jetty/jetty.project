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
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.frames.ControlFrame;

public interface ISession extends Session
{
    /**
     * <p>Initiates the flush of data to the other peer.</p>
     * <p>Note that the flush may do nothing if, for example, there is nothing to flush, or
     * if the data to be flushed belong to streams that have their flow-control stalled.</p>
     */
    public void flush();

    public <C> void control(IStream stream, ControlFrame frame, long timeout, TimeUnit unit, Handler<C> handler, C context);

    public <C> void data(IStream stream, DataInfo dataInfo, long timeout, TimeUnit unit, Handler<C> handler, C context);

    public int getWindowSize();
}
