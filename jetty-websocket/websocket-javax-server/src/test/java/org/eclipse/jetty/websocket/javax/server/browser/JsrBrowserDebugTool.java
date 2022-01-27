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

package org.eclipse.jetty.websocket.javax.server.browser;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Objects;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool to help debug JSR based websocket circumstances reported around browsers.
 * <p>
 * Provides a server, with a few simple websocket's that can be twiddled from a browser. This helps with setting up breakpoints and whatnot to help debug our
 * websocket implementation from the context of a browser client.
 */
public class JsrBrowserDebugTool
{
    private static final Logger LOG = LoggerFactory.getLogger(JsrBrowserDebugTool.class);

    public static void main(String[] args)
    {
        int port = 8080;

        for (int i = 0; i < args.length; i++)
        {
            String a = args[i];
            if ("-p".equals(a) || "--port".equals(a))
            {
                port = Integer.parseInt(args[++i]);
            }
        }

        try
        {
            JsrBrowserDebugTool tool = new JsrBrowserDebugTool();
            tool.setupServer(port);
            tool.server.start();
            LOG.info("Server available at {}", tool.server.getURI());
            tool.server.join();
        }
        catch (Throwable t)
        {
            LOG.warn("Unable to start {}", JsrBrowserDebugTool.class.getName(), t);
        }
    }

    private Server server;

    private void setupServer(int port) throws URISyntaxException, IOException
    {
        server = new Server();

        HttpConfiguration httpConf = new HttpConfiguration();
        httpConf.setSendServerVersion(true);

        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConf));
        connector.setPort(port);
        server.addConnector(connector);

        String resourcePath = "/jsr-browser-debug-tool/index.html";
        URL urlStatics = JsrBrowserDebugTool.class.getResource(resourcePath);
        Objects.requireNonNull(urlStatics, "Unable to find " + resourcePath + " in classpath");
        String urlBase = urlStatics.toURI().toASCIIString().replaceFirst("/[^/]*$", "/");

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.setBaseResource(Resource.newResource(urlBase));

        ServletHolder holder = context.addServlet(DefaultServlet.class, "/");
        holder.setInitParameter("dirAllowed", "true");
        server.setHandler(context);

        JavaxWebSocketServletContainerInitializer.configure(context,
            (servletContext, container) -> container.addEndpoint(JsrBrowserSocket.class));

        LOG.info("{} setup on port {}", this.getClass().getName(), port);
    }
}
