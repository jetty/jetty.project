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

package com.acme;

import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint(value = "/javax.websocket/", subprotocols = {"chat"})
public class JavaxWebSocketChat
{
    private static final List<JavaxWebSocketChat> members = new CopyOnWriteArrayList<>();

    volatile Session session;
    volatile RemoteEndpoint.Async remote;

    @OnOpen
    public void onOpen(Session sess)
    {
        this.session = sess;
        this.remote = this.session.getAsyncRemote();
        members.add(this);
    }

    @OnMessage
    public void onMessage(String data)
    {
        if (data.contains("disconnect"))
        {
            try
            {
                session.close();
            }
            catch (IOException ignore)
            {
                /* ignore */
            }
            return;
        }

        ListIterator<JavaxWebSocketChat> iter = members.listIterator();
        while (iter.hasNext())
        {
            JavaxWebSocketChat member = iter.next();

            // Test if member is now disconnected
            if (!member.session.isOpen())
            {
                iter.remove();
                continue;
            }

            // Async write the message back
            member.remote.sendText(data);
        }
    }

    @OnClose
    public void onClose(CloseReason reason)
    {
        members.remove(this);
    }
}
