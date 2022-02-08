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

package org.eclipse.jetty.tests.webapp.websocket.bad;

import java.io.IOException;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint("/badonopen/{arg}")
public class BadOnOpenServerEndpoint
{
    private static String open = "";

    @OnMessage
    public String echo(String echo)
    {
        return open + echo;
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("arg") StringSequence sb)
    {
        open = sb.toString();
    }

    @OnError
    public void onError(Session session, Throwable t)
        throws IOException
    {
        String message = "Error happened:" + t.getMessage();
        session.getBasicRemote().sendText(message);
    }
}
