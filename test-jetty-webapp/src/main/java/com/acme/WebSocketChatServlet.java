package com.acme;
//========================================================================
//Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.server.WebSocketServlet;

public class WebSocketChatServlet extends WebSocketServlet
{
    private static final Logger LOG = Log.getLogger(WebSocketChatServlet.class);

    private final Set<ChatWebSocket> _members = new CopyOnWriteArraySet<ChatWebSocket>();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
        throws javax.servlet.ServletException ,IOException 
    {
        getServletContext().getNamedDispatcher("default").forward(request,response);
    };

    @Override
    public void registerWebSockets(WebSocketServerFactory factory)
    {
        factory.register(ChatWebSocket.class);
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    @WebSocket
    class ChatWebSocket
    {
        volatile WebSocketConnection _connection;

        @OnWebSocketConnect
        public void onOpen(WebSocketConnection conn)
        {
            _connection = conn;
            _members.add(this);
        }

        @OnWebSocketMessage
        public void onMessage(String data)
        {
            if (data.indexOf("disconnect")>=0)
            {
                try
                {
                    _connection.close();
                }
                catch(IOException e)
                {
                    LOG.warn(e);
                }
            }
            else
            {
                // LOG.info(this+" onMessage: "+data);
                for (ChatWebSocket member : _members)
                {
                    try
                    {
                        member._connection.write(null,new FutureCallback<>(),data);
                    }
                    catch(IOException e)
                    {
                        LOG.warn(e);
                    }
                }
            }
        }
        
        @OnWebSocketClose
        public void onClose(int code, String message)
        {
            _members.remove(this);
        }

    }
}
