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
