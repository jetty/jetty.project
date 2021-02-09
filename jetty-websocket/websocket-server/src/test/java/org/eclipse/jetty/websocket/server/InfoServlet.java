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

package org.eclipse.jetty.websocket.server;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

public class InfoServlet extends HttpServlet implements WebSocketCreator
{
    @Override
    public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
    {
        return new InfoSocket();
    }

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        ServletContext context = config.getServletContext();
        NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration)context.getAttribute(NativeWebSocketConfiguration.class.getName());
        configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
        configuration.addMapping("/info/*", this);
    }
}
