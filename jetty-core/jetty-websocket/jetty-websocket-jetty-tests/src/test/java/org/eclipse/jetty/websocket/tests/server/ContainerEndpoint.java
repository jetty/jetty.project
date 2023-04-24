//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests.server;

import java.util.Collection;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketContainer;

/**
 * On Message, return container information
 */
public class ContainerEndpoint extends AbstractCloseEndpoint
{
    private final WebSocketContainer container;
    private Session session;

    public ContainerEndpoint(WebSocketContainer container)
    {
        super();
        this.container = container;
    }

    @Override
    public void onWebSocketText(String message)
    {
        log.debug("onWebSocketText({})", message);
        if (message.equalsIgnoreCase("openSessions"))
        {
            Collection<Session> sessions = container.getOpenSessions();

            StringBuilder ret = new StringBuilder();
            ret.append("openSessions.size=").append(sessions.size()).append('\n');
            int idx = 0;
            for (Session sess : sessions)
            {
                ret.append('[').append(idx++).append("] ").append(sess.toString()).append('\n');
            }
            session.sendText(ret.toString(), Callback.NOOP);
        }
        session.close(StatusCode.NORMAL, "ContainerEndpoint", Callback.NOOP);
    }

    @Override
    public void onWebSocketOpen(Session sess)
    {
        log.debug("onWebSocketOpen({})", sess);
        this.session = sess;
    }
}
