/*******************************************************************************
 * Copyright (c) 2011 Intalio, Inc.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/
// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.websocket.server;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;

public abstract class WebSocketHandler extends HandlerWrapper implements WebSocketServerFactory.Acceptor
{
    private final WebSocketServerFactory webSocketFactory;

    public WebSocketHandler()
    {
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        webSocketFactory = new WebSocketServerFactory(this,policy);
    }

    @Override
    public boolean checkOrigin(HttpServletRequest request, String origin)
    {
        return true;
    }

    public WebSocketServerFactory getWebSocketFactory()
    {
        return webSocketFactory;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (webSocketFactory.acceptWebSocket(request,response) || response.isCommitted())
        {
            return;
        }
        super.handle(target,baseRequest,request,response);
    }
}
