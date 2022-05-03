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

package org.eclipse.jetty.ee10.tests.webapp.websocket.bad;

import java.io.IOException;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/badonclose/{arg}")
public class BadOnCloseServerEndpoint
{
    private static String close = "";

    @OnMessage
    public String echo(String echo)
    {
        return close + echo;
    }

    @OnClose
    public void onClose(Session session, @PathParam("arg") StringSequence sb)
    {
        close = sb.toString();
    }

    @OnError
    public void onError(Session session, Throwable t)
        throws IOException
    {
        String message = "Error happened:" + t.getMessage();
        session.getBasicRemote().sendText(message);
    }
}
