//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server.browser;

import javax.websocket.DeploymentException;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;

/**
 * Tool to help debug JSR based websocket circumstances reported around browsers.
 * <p>
 * Provides a server, with a few simple websocket's that can be twiddled from a browser. This helps with setting up breakpoints and whatnot to help debug our
 * websocket implementation from the context of a browser client.
 */
public class JsrBrowserDebugTool
{
    private static final Logger LOG = Log.getLogger(JsrBrowserDebugTool.class);

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
            tool.runForever();
        }
        catch (Throwable t)
        {
            LOG.warn(t);
        }
    }

    private Server server;

    private void runForever() throws Exception
    {
        server.start();
        server.dumpStdErr();
        LOG.info("Server available.");
        server.join();
    }

    private void setupServer(int port) throws DeploymentException
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        ServletHolder holder = context.addServlet(DefaultServlet.class,"/");
        holder.setInitParameter("resourceBase","src/test/resources/jsr-browser-debug-tool");
        holder.setInitParameter("dirAllowed","true");
        server.setHandler(context);

        ServerContainer container = WebSocketServerContainerInitializer.configureContext(context);
        container.addEndpoint(JsrBrowserSocket.class);

        LOG.info("{} setup on port {}",this.getClass().getName(),port);
    }
}
