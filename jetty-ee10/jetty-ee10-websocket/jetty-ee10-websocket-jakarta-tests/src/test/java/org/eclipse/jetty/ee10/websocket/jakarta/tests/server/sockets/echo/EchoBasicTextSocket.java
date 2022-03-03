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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.server.sockets.echo;

import java.io.IOException;

import jakarta.websocket.CloseReason;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/echo/text/basic")
public class EchoBasicTextSocket
{
    private Session session;

    @OnOpen
    public void onOpen(Session session)
    {
        this.session = session;
    }

    @OnMessage
    public void onText(String msg)
    {
        try
        {
            session.getBasicRemote().sendText(msg);
        }
        catch (IOException esend)
        {
            esend.printStackTrace(System.err);
            try
            {
                session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(4001), "Unable to echo msg"));
            }
            catch (IOException eclose)
            {
                eclose.printStackTrace();
            }
        }
    }
}
