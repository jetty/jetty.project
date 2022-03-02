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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server.sockets.echo;

import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;

/**
 * Example of websocket endpoint based on extending {@link Endpoint}
 */
public class BasicEchoEndpoint extends Endpoint implements MessageHandler.Whole<String>
{
    private Session session;

    @Override
    public void onMessage(String msg)
    {
        // reply with echo
        session.getAsyncRemote().sendText(msg);
    }

    @Override
    public void onOpen(Session session, EndpointConfig config)
    {
        this.session = session;
        this.session.addMessageHandler(this);
    }
}
