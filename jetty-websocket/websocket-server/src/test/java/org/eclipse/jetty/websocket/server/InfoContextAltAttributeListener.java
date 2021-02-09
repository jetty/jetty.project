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

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

public class InfoContextAltAttributeListener implements WebSocketCreator, ServletContextListener
{
    private static final String ATTR = "alt.config";

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration(sce.getServletContext());
        configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
        configuration.addMapping("/info/*", this);
        sce.getServletContext().setAttribute(ATTR, configuration);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
    }

    @Override
    public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
    {
        return new InfoSocket();
    }
}
