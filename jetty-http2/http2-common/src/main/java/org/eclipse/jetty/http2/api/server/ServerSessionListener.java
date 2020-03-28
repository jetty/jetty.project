//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
    public void onAccept(Session session);

    /**
     * <p>Empty implementation of {@link ServerSessionListener}</p>
     */
    public static class Adapter extends Session.Listener.Adapter implements ServerSessionListener
    {
        @Override
        public void onAccept(Session session)
        {
        }
    }
}
