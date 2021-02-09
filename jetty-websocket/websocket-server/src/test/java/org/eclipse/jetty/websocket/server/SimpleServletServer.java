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

import java.net.URI;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

public class SimpleServletServer
{
    private static final Logger LOG = Log.getLogger(SimpleServletServer.class);
    private Server server;
    private ServerConnector connector;
    private URI serverUri;
    private HttpServlet servlet;
    private boolean ssl = false;
    private SslContextFactory sslContextFactory;

    public SimpleServletServer(HttpServlet servlet)
    {
        this.servlet = servlet;
    }

    public void enableSsl(boolean ssl)
    {
        this.ssl = ssl;
    }

    public URI getServerUri()
    {
        return serverUri;
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    public boolean isSslEnabled()
    {
        return ssl;
    }

    public void start() throws Exception
    {
        // Configure Server
        server = new Server();
        if (ssl)
        {
            // HTTP Configuration
            HttpConfiguration httpConfig = new HttpConfiguration();
            httpConfig.setSecureScheme("https");
            httpConfig.setSecurePort(0);
            httpConfig.setOutputBufferSize(32768);
            httpConfig.setRequestHeaderSize(8192);
            httpConfig.setResponseHeaderSize(8192);
            httpConfig.setSendServerVersion(true);
            httpConfig.setSendDateHeader(false);

            sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setKeyStorePath(MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath());
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setKeyManagerPassword("keypwd");

            // SSL HTTP Configuration
            HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
            httpsConfig.addCustomizer(new SecureRequestCustomizer());

            // SSL Connector
            connector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()), new HttpConnectionFactory(httpsConfig));
            connector.setPort(0);
        }
        else
        {
            // Basic HTTP connector
            connector = new ServerConnector(server);
            connector.setPort(0);
        }
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        configureServletContextHandler(context);
        server.setHandler(context);

        // Serve capture servlet
        context.addServlet(new ServletHolder(servlet), "/*");

        // Start Server
        server.start();

        // Establish the Server URI
        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("%s://%s:%d/", ssl ? "wss" : "ws", host, port));

        // Some debugging
        if (LOG.isDebugEnabled())
        {
            LOG.debug(server.dump());
        }
    }

    protected void configureServletContextHandler(ServletContextHandler context)
    {
    }

    public void stop()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }
    }

    public WebSocketServletFactory getWebSocketServletFactory()
    {
        // Try filter approach first
        WebSocketUpgradeFilter filter = (WebSocketUpgradeFilter)this.servlet.getServletContext().getAttribute(WebSocketUpgradeFilter.class.getName());
        if (filter != null)
        {
            return filter.getFactory();
        }

        // Try servlet next
        return (WebSocketServletFactory)this.servlet.getServletContext().getAttribute(WebSocketServletFactory.class.getName());
    }
}
