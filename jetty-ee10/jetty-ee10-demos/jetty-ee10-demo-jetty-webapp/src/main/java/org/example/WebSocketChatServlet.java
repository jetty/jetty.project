//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.example;

import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketCreator;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@SuppressWarnings("serial")
public class WebSocketChatServlet extends JettyWebSocketServlet implements JettyWebSocketCreator
{
    /**
     * Holds active sockets to other members of the chat
     */
    private final List<ChatWebSocket> members = new CopyOnWriteArrayList<ChatWebSocket>();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        getServletContext().getNamedDispatcher("default").forward(request, response);
    }

    @Override
    public Object createWebSocket(JettyServerUpgradeRequest req, JettyServerUpgradeResponse resp)
    {
        if (req.hasSubProtocol("chat"))
        {
            resp.setAcceptedSubProtocol("chat");
            return new ChatWebSocket();
        }
        return null;
    }

    @Override
    public void configure(JettyWebSocketServletFactory factory)
    {
        factory.addMapping("/", this);
    }

    /**
     * Create a WebSocket that echo's back the message to all other members of the servlet.
     */
    @WebSocket
    public class ChatWebSocket
    {
        volatile Session session;

        @OnWebSocketOpen
        public void onOpen(Session session)
        {
            this.session = session;
            members.add(this);
        }

        @OnWebSocketMessage
        public void onMessage(String data)
        {
            if (data.contains("disconnect"))
            {
                session.close();
                return;
            }

            ListIterator<ChatWebSocket> iter = members.listIterator();
            while (iter.hasNext())
            {
                ChatWebSocket member = iter.next();

                // Test if member is now disconnected
                if (!member.session.isOpen())
                {
                    iter.remove();
                    continue;
                }

                // Async write the message back.
                member.session.sendText(data, null);
            }
        }

        @OnWebSocketClose
        public void onClose(int code, String message)
        {
            members.remove(this);
        }
    }
}
