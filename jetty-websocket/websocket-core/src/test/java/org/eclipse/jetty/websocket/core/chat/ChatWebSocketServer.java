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

import java.io.IOException;
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
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.server.RFC6455Handshaker;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;

public class ChatWebSocketServer implements FrameHandler
{
    public static void main(String[] args) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory());

        connector.addBean(new WebSocketPolicy(WebSocketBehavior.SERVER));
        connector.addBean(new RFC6455Handshaker());
        connector.setPort(8888);
        connector.setIdleTimeout(1000000);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");
        server.setHandler(context);
        WebSocketNegotiator negotiator =  new ChatWebSocketNegotiator(new DecoratedObjectFactory(), new WebSocketExtensionRegistry(), connector.getByteBufferPool());

        WebSocketUpgradeHandler handler = new WebSocketUpgradeHandler(negotiator);
        context.setHandler(handler);
        handler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().println("Hello World!");
                baseRequest.setHandled(true);
            }
        });

        server.start();
        server.join();
    }


    private static Logger LOG = Log.getLogger(ChatWebSocketServer.class);

    private CoreSession channel;
    private Set<CoreSession> channelSet;

    public ChatWebSocketServer(Set<CoreSession> channels)
    {
        channelSet = channels;
    }

    @Override
    public void onOpen(CoreSession coreSession) throws Exception
    {
        LOG.info("onOpen {}",coreSession);
        this.channel = coreSession;
        this.channelSet.add(coreSession);
    }

    @Override
    public void onReceiveFrame(org.eclipse.jetty.websocket.core.frames.Frame frame, Callback callback)
    {
        String message = BufferUtil.toString(frame.getPayload());
        for (CoreSession channel : channelSet)
        {
            LOG.info("Sending Message: " + message);
            channel.sendFrame(new Frame(OpCode.TEXT).setPayload(message), Callback.NOOP, BatchMode.AUTO);
        }

        callback.succeeded();
    }


    @Override
    public void onClosed(CloseStatus closeStatus) throws Exception
    {
        LOG.info("onClosed {}",closeStatus);
        channel.close(Callback.NOOP);
        channelSet.remove(channel);
        channel = null;
    }

    @Override
    public void onError(Throwable cause) throws Exception
    {
        LOG.warn("onError",cause);
        channel.close(Callback.NOOP);
        channelSet.remove(channel);
        channel = null;
    }
}
