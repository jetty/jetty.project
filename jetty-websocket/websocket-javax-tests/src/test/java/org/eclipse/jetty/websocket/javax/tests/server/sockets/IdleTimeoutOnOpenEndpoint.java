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

package org.eclipse.jetty.websocket.javax.tests.server.sockets;

import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;

public class IdleTimeoutOnOpenEndpoint extends Endpoint implements MessageHandler.Whole<String>
{
    private Session session;

    @Override
    public void onOpen(Session session, EndpointConfig config)
    {
        this.session = session;
        session.addMessageHandler(this);
        session.setMaxIdleTimeout(500);
    }

    @Override
    public void onMessage(String message)
    {
        // echo message back (this is an indication of timeout failure)
        session.getAsyncRemote().sendText(message);
    }
}
