//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.api.WriteCallback;

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
            session.getRemote().sendString(ret.toString(), WriteCallback.NOOP);
        }
        session.close(StatusCode.NORMAL, "ContainerEndpoint");
    }

    @Override
    public void onWebSocketConnect(Session sess)
    {
        log.debug("onWebSocketConnect({})", sess);
        this.session = sess;
    }
}
