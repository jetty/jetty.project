// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.server;

import java.net.URI;

import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.server.SelectChannelConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class SimpleServletServer
{
    private Server server;
    private SelectChannelConnector connector;
    private URI serverUri;
    private HttpServlet servlet;

    public SimpleServletServer(HttpServlet servlet)
    {
        this.servlet = servlet;
    }

    public URI getServerUri()
    {
        return serverUri;
    }

    public void start() throws Exception
    {
        // Configure Server
        server = new Server();
        connector = new SelectChannelConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        // Serve capture servlet
        context.addServlet(new ServletHolder(servlet),"/*");

        // Start Server
        server.start();

        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("ws://%s:%d/",host,port));
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
}
