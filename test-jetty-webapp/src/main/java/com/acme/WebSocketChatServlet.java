//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

//

//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.server.WebSocketCreator;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.server.WebSocketServlet;

@SuppressWarnings("serial")
public class WebSocketChatServlet extends WebSocketServlet implements WebSocketCreator
{
    private static final Logger LOG = Log.getLogger(WebSocketChatServlet.class);

    /** Holds active sockets to other members of the chat */
    private final List<ChatWebSocket> members = new CopyOnWriteArrayList<ChatWebSocket>();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        getServletContext().getNamedDispatcher("default").forward(request,response);
    }

    @Override
    public Object createWebSocket(UpgradeRequest req, UpgradeResponse resp)
    {
        return new ChatWebSocket();
    }

    @Override
    public void registerWebSockets(WebSocketServerFactory factory)
    {
        factory.register(ChatWebSocket.class);
        factory.setCreator(this);
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a WebSocket that echo's back the message to all other members of the servlet.
     */
    @WebSocket
    public class ChatWebSocket
    {
        volatile WebSocketConnection connection;

        @OnWebSocketConnect
        public void onOpen(WebSocketConnection conn)
        {
            connection = conn;
            members.add(this);
        }

        @OnWebSocketMessage
        public void onMessage(String data)
        {
            if (data.contains("disconnect"))
            {
                connection.close();
                return;
            }

            ListIterator<ChatWebSocket> iter = members.listIterator();
            while (iter.hasNext())
            {
                ChatWebSocket member = iter.next();

                // Test if member is now disconnected
                if (!member.connection.isOpen())
                {
                    iter.remove();
                    continue;
                }

                try
                {
                    // Async write the message back.
                    member.connection.write(null,new FutureCallback<>(),data);
                }
                catch (IOException e)
                {
                    LOG.warn(e);
                }
            }
        }

        @OnWebSocketClose
        public void onClose(int code, String message)
        {
            members.remove(this);
        }
    }
}
