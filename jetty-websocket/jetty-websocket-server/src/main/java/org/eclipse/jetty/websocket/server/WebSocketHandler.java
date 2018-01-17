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

package org.eclipse.jetty.websocket.server;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.HandshakerFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketServletNegotiator;

public abstract class WebSocketHandler extends HandlerWrapper
{
    private WebSocketServletFactory factory;
    private WebSocketServletNegotiator negotiator;

    public abstract void configure(WebSocketServletFactory factory);

    @Override
    protected void doStart() throws Exception
    {
        factory = new WebSocketServletFactory(new WebSocketPolicy(WebSocketBehavior.SERVER), null, null, null);
        addBean(factory);
        configure(factory);
        negotiator = new WebSocketServletNegotiator(factory, factory.getCreator());
        super.doStart();
    }
    
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        Handshaker handshaker = HandshakerFactory.getHandshaker(request);

        // Attempt to upgrade
        if (handshaker != null && handshaker.upgradeRequest(negotiator, request, response))
        {
            // Upgrade was a success, nothing else to do.
            baseRequest.setHandled(true);
            return;
        }

        // If we reach this point, it means we had an incoming request to upgrade
        // but it was either not a proper websocket upgrade, or it was possibly rejected
        // due to incoming request constraints (controlled by WebSocketCreator)
        if (response.isCommitted())
        {
            // not much we can do at this point.
            baseRequest.setHandled(true);
            return;
        }

        // All other processing
        super.handle(target,baseRequest,request,response);
    }
}
