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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server.sockets.primitives;

import java.io.IOException;

import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.toolchain.test.StackUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServerEndpoint("/echo/primitives/booleanobject/params/{a}")
public class BooleanObjectTextParamSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(BooleanObjectTextParamSocket.class);

    private Session session;

    @OnOpen
    public void onOpen(Session session)
    {
        this.session = session;
    }

    @OnMessage
    public void onMessage(Boolean b, @PathParam("a") Boolean param) throws IOException
    {
        if (b == null)
        {
            session.getAsyncRemote().sendText("Error: Boolean is null");
        }
        else
        {
            String msg = String.format("%b|%b", b, param);
            session.getAsyncRemote().sendText(msg);
        }
    }

    @OnError
    public void onError(Throwable cause) throws IOException
    {
        LOG.warn("Error", cause);
        session.getBasicRemote().sendText("Exception: " + StackUtils.toString(cause));
    }
}
