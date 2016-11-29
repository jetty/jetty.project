//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

public abstract class WebSocketHandler extends HandlerWrapper
{
    /**
     * Create a simple WebSocketHandler that registers a single WebSocket POJO that is created on every upgrade request.
     */
    public static class Simple extends WebSocketHandler
    {
        private Class<?> websocketPojo;

        public Simple(Class<?> websocketClass)
        {
            super();
            this.websocketPojo = websocketClass;
        }

        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.register(websocketPojo);
        }
    }
    
    private final ByteBufferPool bufferPool;
    private WebSocketServletFactory webSocketFactory;

    public WebSocketHandler()
    {
        this(new MappedByteBufferPool());
    }
    
    public WebSocketHandler(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
    }

    public abstract void configure(WebSocketServletFactory factory);

    public void configurePolicy(WebSocketPolicy policy)
    {
        /* leave at default */
    }

    @Override
    protected void doStart() throws Exception
    {
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        configurePolicy(policy);
        webSocketFactory = new WebSocketServerFactory(policy, getServer().getThreadPool(), bufferPool);
        addBean(webSocketFactory);
        configure(webSocketFactory);
        super.doStart();
    }
    
    public WebSocketServletFactory getWebSocketFactory()
    {
        if (!isRunning())
            throw new IllegalStateException("Not Started yet");
        return webSocketFactory;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (webSocketFactory.isUpgradeRequest(request,response))
        {
            // We have an upgrade request
            if (webSocketFactory.acceptWebSocket(request,response))
            {
                // We have a socket instance created
                baseRequest.setHandled(true);
                return;
            }
            // If we reach this point, it means we had an incoming request to upgrade
            // but it was either not a proper websocket upgrade, or it was possibly rejected
            // due to incoming request constraints (controlled by WebSocketCreator)
            if (response.isCommitted())
            {
                // not much we can do at this point.
                return;
            }
        }
        super.handle(target,baseRequest,request,response);
    }
}
