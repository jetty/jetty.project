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

package org.eclipse.jetty.websocket.common.annotations;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Example of a stateless websocket implementation.
 * <p>
 * Useful for websockets that only reply to incoming requests.
 * <p>
 * Note: that for this style of websocket to be viable on the server side be sure that you only create 1 instance of this socket, as more instances would be
 * wasteful of resources and memory.
 */
@WebSocket
public class MyStatelessEchoSocket
{
    @OnWebSocketMessage
    public void onText(Session session, String text)
    {
        session.getRemote().sendString(text, null);
    }
}
