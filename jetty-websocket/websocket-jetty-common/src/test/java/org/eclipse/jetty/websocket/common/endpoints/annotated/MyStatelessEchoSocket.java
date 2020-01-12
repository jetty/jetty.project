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

package org.eclipse.jetty.websocket.common.endpoints.annotated;

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
