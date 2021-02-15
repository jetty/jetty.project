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

package org.eclipse.jetty.websocket.tests.server;

import java.util.Collection;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;

/**
 * On Message, return container information
 */
public class ContainerEndpoint extends AbstractCloseEndpoint
{
    private final WebSocketServerFactory container;
    private Session session;

    public ContainerEndpoint(WebSocketServerFactory container)
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
            Collection<WebSocketSession> sessions = container.getOpenSessions();

            StringBuilder ret = new StringBuilder();
            ret.append("openSessions.size=").append(sessions.size()).append('\n');
            int idx = 0;
            for (WebSocketSession sess : sessions)
            {
                ret.append('[').append(idx++).append("] ").append(sess.toString()).append('\n');
            }
            session.getRemote().sendStringByFuture(ret.toString());
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
