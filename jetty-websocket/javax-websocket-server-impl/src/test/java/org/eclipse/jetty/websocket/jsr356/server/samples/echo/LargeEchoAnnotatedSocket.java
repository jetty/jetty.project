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

package org.eclipse.jetty.websocket.jsr356.server.samples.echo;

import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

/**
 * Annotated echo socket
 */
@ServerEndpoint(value = "/echo/large")
public class LargeEchoAnnotatedSocket
{
    private Session session;

    @OnOpen
    public void open(Session session)
    {
        this.session = session;
    }

    @OnMessage(maxMessageSize = 128 * 1024)
    public void echo(String msg)
    {
        // reply with echo
        session.getAsyncRemote().sendText(msg);
    }

    @SuppressWarnings("unused")
    @OnError
    public void onError(Throwable cause)
    {
    }
}
