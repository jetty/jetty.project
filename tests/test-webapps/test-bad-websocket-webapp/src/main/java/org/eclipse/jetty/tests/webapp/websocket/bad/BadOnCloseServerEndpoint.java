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

package org.eclipse.jetty.tests.webapp.websocket.bad;

import java.io.IOException;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

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
