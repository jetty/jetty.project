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

package org.eclipse.jetty.spdy.api.server;

import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;

/**
 * <p>Specific, server-side, {@link SessionFrameListener}.</p>
 * <p>In addition to {@link SessionFrameListener}, this listener adds method
 * {@link #onConnect(Session)} that is called when a client first connects to the
 * server and may be used by a server-side application to send a SETTINGS frame
 * to configure the connection before the client can open any stream.</p>
 */
public interface ServerSessionFrameListener extends SessionFrameListener
{
    /**
     * <p>Callback invoked when a client opens a connection.</p>
     *
     * @param session the session
     */
    public void onConnect(Session session);

    /**
     * <p>Empty implementation of {@link ServerSessionFrameListener}</p>
     */
    public static class Adapter extends SessionFrameListener.Adapter implements ServerSessionFrameListener
    {
        @Override
        public void onConnect(Session session)
        {
        }
    }
}
