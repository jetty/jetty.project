//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http2.api.server;

import org.eclipse.jetty.http2.api.Session;

/**
 * <p>Server-side extension of {@link org.eclipse.jetty.http2.api.Session.Listener} that exposes additional events.</p>
 */
public interface ServerSessionListener extends Session.Listener
{
    /**
     * <p>Callback method invoked when a connection has been accepted by the server.</p>
     *
     * @param session the session
     */
    void onAccept(Session session);

    /**
     * <p>Empty implementation of {@link ServerSessionListener}</p>
     */
    class Adapter extends Session.Listener.Adapter implements ServerSessionListener
    {
        @Override
        public void onAccept(Session session)
        {
        }
    }
}
