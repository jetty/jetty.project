//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.chat;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.MessageHandler;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;

import static org.eclipse.jetty.util.Callback.NOOP;

public class ChatWebSocketServer
{
    private static Logger LOG = Log.getLogger(ChatWebSocketServer.class);

    private Set<MessageHandler> members = new HashSet<>();

    private FrameHandler negotiate(Negotiation negotiation)
    {
        // Finalize negotiations in API layer involves:
        //  + MAY mutate the policy
        //  + MAY replace the policy
        //  + MAY read request and set response headers
        //  + MAY reject with sendError semantics
        //  + MAY change/add/remove offered extensions
        //  + MUST pick subprotocol
        List<String> subprotocols = negotiation.getOfferedSubprotocols();
        if (!subprotocols.contains("chat"))
            return null;
        negotiation.setSubprotocol("chat");

        //  + MUST return the FrameHandler or null or exception?
        return new MessageHandler()
        {
            @Override
            public void onOpen(CoreSession coreSession, Callback callback)
            {
                LOG.debug("onOpen {}", coreSession);
                coreSession.setMaxTextMessageSize(2 * 1024);
                super.onOpen(coreSession, Callback.from(() ->
                {
                    members.add(this);
                    callback.succeeded();
                }, x -> callback.failed(x)));
            }

            @Override
            public void onText(String message, Callback callback)
            {
                for (MessageHandler handler : members)
                {
                    if (handler == this)
                        continue;
                    LOG.debug("Sending Message{} to {}", message, handler);
                    handler.sendText(message, NOOP, false);
                }

                callback.succeeded();
            }

            @Override
            public void onClosed(CloseStatus closeStatus, Callback callback)
            {
                LOG.debug("onClosed {}", closeStatus);
                super.onClosed(closeStatus, Callback.from(() -> members.remove(this), callback));
                members.remove(this);
            }
        };
    }

    public static void main(String[] args) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory());

        connector.setPort(8888);
        connector.setIdleTimeout(1000000);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");
        server.setHandler(context);

        ChatWebSocketServer chat = new ChatWebSocketServer();
        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler(WebSocketNegotiator.from(chat::negotiate));
        context.setHandler(upgradeHandler);

        upgradeHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                    throws IOException, ServletException
            {
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().println("WebSocket Chat Server");
                baseRequest.setHandled(true);
            }
        });

        server.start();
        server.join();
    }
}
