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

package org.eclipse.jetty.ee10.websocket.server.browser;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.api.ExtensionConfig;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketCreator;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool to help debug websocket circumstances reported around browsers.
 * <p>
 * Provides a server, with a few simple websocket's that can be twiddled from a browser. This helps with setting up breakpoints and whatnot to help debug our
 * websocket implementation from the context of a browser client.
 */
public class BrowserDebugTool
{
    private static final Logger LOG = LoggerFactory.getLogger(BrowserDebugTool.class);

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
            BrowserDebugTool tool = new BrowserDebugTool();
            tool.prepare(port);
            tool.start();
        }
        catch (Throwable t)
        {
            LOG.warn("Unable to start {}", BrowserDebugTool.class.getName(), t);
        }
    }

    private Server server;
    private ServerConnector connector;

    public int getPort()
    {
        return connector.getLocalPort();
    }

    public void prepare(int port)
    {
        server = new Server();

        // Setup JMX
        // MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        // server.addBean(mbContainer, true);

        // Setup Connector
        connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();

        JettyWebSocketServletContainerInitializer.configure(context, null);

        context.setContextPath("/");
        Resource staticResourceBase = findStaticResources();
        context.setBaseResource(staticResourceBase.getPath());
        context.addServlet(BrowserSocketServlet.class, "/*");
        ServletHolder defHolder = new ServletHolder("default", DefaultServlet.class);
        context.addServlet(defHolder, "/");

        server.setHandler(new HandlerList(context, new DefaultHandler()));

        LOG.info("{} setup on port {}", this.getClass().getName(), port);
    }

    private Resource findStaticResources()
    {
        Path path = MavenTestingUtils.getTestResourcePathDir("browser-debug-tool");
        LOG.info("Static Resources: {}", path);
        return ResourceFactory.ROOT.newResource(path);
    }

    public void start() throws Exception
    {
        server.start();
        LOG.info("Server available on port {}", getPort());
    }

    public void stop() throws Exception
    {
        server.stop();
    }

    public static class BrowserSocketServlet extends JettyWebSocketServlet
    {
        @Override
        public void configure(JettyWebSocketServletFactory factory)
        {
            LOG.debug("Configuring WebSocketServerFactory ...");

            // Setup the desired Socket to use for all incoming upgrade requests
            factory.addMapping("/", new BrowserSocketCreator());

            // Set the timeout
            factory.setIdleTimeout(Duration.ofSeconds(30));

            // Set top end message size
            factory.setMaxTextMessageSize(15 * 1024 * 1024);
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            request.getServletContext().getNamedDispatcher("default").forward(request, response);
        }
    }

    public static class BrowserSocketCreator implements JettyWebSocketCreator
    {
        @Override
        public Object createWebSocket(JettyServerUpgradeRequest req, JettyServerUpgradeResponse resp)
        {
            LOG.debug("Creating BrowserSocket");

            if (req.getSubProtocols() != null)
            {
                if (!req.getSubProtocols().isEmpty())
                {
                    String subProtocol = req.getSubProtocols().get(0);
                    resp.setAcceptedSubProtocol(subProtocol);
                }
            }

            String ua = req.getHeader("User-Agent");
            String rexts = req.getHeader("Sec-WebSocket-Extensions");

            // manually negotiate extensions
            List<ExtensionConfig> negotiated = new ArrayList<>();
            // adding frame debug
            negotiated.add(ExtensionConfig.parse("@frame-capture; output-dir=target"));
            for (ExtensionConfig config : req.getExtensions())
            {
                if (config.getName().equals("permessage-deflate"))
                {
                    // what we are interested in here
                    negotiated.add(config);
                    continue;
                }
                // skip all others
            }

            resp.setExtensions(negotiated);

            LOG.debug("User-Agent: {}", ua);
            LOG.debug("Sec-WebSocket-Extensions (Request) : {}", rexts);
            LOG.debug("Sec-WebSocket-Protocol (Request): {}", req.getHeader("Sec-WebSocket-Protocol"));
            LOG.debug("Sec-WebSocket-Protocol (Response): {}", resp.getAcceptedSubProtocol());

            req.getExtensions();
            return new BrowserSocket(ua, rexts);
        }
    }
}
