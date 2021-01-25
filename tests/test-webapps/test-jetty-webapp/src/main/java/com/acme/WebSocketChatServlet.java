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

package com.acme;

import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

@SuppressWarnings("serial")
public class WebSocketChatServlet extends WebSocketServlet implements WebSocketCreator
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
    public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
    {
        if (req.hasSubProtocol("chat"))
        {
            resp.setAcceptedSubProtocol("chat");
            return new ChatWebSocket();
        }
        return null;
    }

    @Override
    public void configure(WebSocketServletFactory factory)
    {
        factory.register(ChatWebSocket.class);
        factory.setCreator(this);
    }

    /**
     * Create a WebSocket that echo's back the message to all other members of the servlet.
     */
    @WebSocket
    public class ChatWebSocket
    {
        volatile Session session;
        volatile RemoteEndpoint remote;

        @OnWebSocketConnect
        public void onOpen(Session sess)
        {
            this.session = sess;
            this.remote = sess.getRemote();
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
                member.remote.sendString(data, null);
            }
        }

        @OnWebSocketClose
        public void onClose(int code, String message)
        {
            members.remove(this);
        }
    }
}
