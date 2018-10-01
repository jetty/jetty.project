//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.BatchMode;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.TestUpgradeHandler;
import org.eclipse.jetty.websocket.core.TextMessageHandler;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.server.internal.RFC6455Handshaker;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChatWebSocketServer extends TextMessageHandler
{
    private static Logger LOG = Log.getLogger(ChatWebSocketServer.class);

    private Set<TextMessageHandler> members = new HashSet<>();

    private FrameHandler negotiate(Negotiation negotiation)
    {
        // Finalize negotiations in API layer involves:
        // TODO need access to real request/response????
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
        return new TextMessageHandler()
        {

            @Override
            public void onOpen(CoreSession coreSession) throws Exception
            {
                LOG.debug("onOpen {}",coreSession);
                super.onOpen(coreSession);
                members.add(this);
            }

            @Override
            public void onText(String message, Callback callback)
            {
                for (TextMessageHandler handler : members)
                {
                    if (handler==this)
                        continue;
                    LOG.debug("Sending Message{} to {}" ,message,  handler);
                    handler.sendText(message, Callback.NOOP, BatchMode.AUTO);
                }

                callback.succeeded();
            }

            @Override
            public void onClosed(CloseStatus closeStatus)
            {
                LOG.debug("onClosed {}",closeStatus);
                super.onClosed(closeStatus);
                members.remove(this);
            }
        };
    }


    public static void main(String[] args) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory());

        connector.addBean(new WebSocketPolicy());
        connector.addBean(new RFC6455Handshaker());
        connector.setPort(8888);
        connector.setIdleTimeout(1000000);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");
        server.setHandler(context);

        ChatWebSocketServer chat = new ChatWebSocketServer();
        WebSocketUpgradeHandler handler = new TestUpgradeHandler(WebSocketNegotiator.from(chat::negotiate));
        handler.getWebSocketNegotiator().getCandidatePolicy().setMaxTextMessageSize(2048);
        context.setHandler(handler);

        server.start();
        server.join();
    }

}
