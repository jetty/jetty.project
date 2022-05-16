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

package org.eclipse.jetty.websocket.core.chat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.internal.MessageHandler;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.util.Callback.NOOP;

public class ChatWebSocketServer
{
    protected static final Logger LOG = LoggerFactory.getLogger(ChatWebSocketServer.class);

    private final Set<MessageHandler> members = new HashSet<>();

    private FrameHandler negotiate(WebSocketNegotiation negotiation)
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
                }, callback::failed));
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
        WebSocketComponents components = new WebSocketComponents();
        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler(components);
        upgradeHandler.addMapping(new ServletPathSpec("/*"), WebSocketNegotiator.from(chat::negotiate));
        context.setHandler(upgradeHandler);

        upgradeHandler.setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                response.write(true, callback, "WebSocket Chat Server");
            }
        });

        server.start();
        server.join();
    }
}
