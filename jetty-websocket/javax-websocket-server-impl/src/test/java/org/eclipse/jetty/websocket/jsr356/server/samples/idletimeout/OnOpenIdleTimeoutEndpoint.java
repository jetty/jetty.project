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

package org.eclipse.jetty.websocket.jsr356.server.samples.idletimeout;

import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;

public class OnOpenIdleTimeoutEndpoint extends Endpoint implements MessageHandler.Whole<String>
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
