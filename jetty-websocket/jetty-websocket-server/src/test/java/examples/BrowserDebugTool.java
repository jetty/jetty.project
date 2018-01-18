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

package examples;

import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.PathResource;

/**
 * Tool to help debug websocket circumstances reported around browsers.
 * <p>
 * Provides a server, with a few simple websocket's that can be twiddled from a browser. This helps with setting up breakpoints and whatnot to help debug our
 * websocket implementation from the context of a browser client.
 */
public class BrowserDebugTool
{
    private static final Logger LOG = Log.getLogger(BrowserDebugTool.class);

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
            LOG.warn(t);
        }
    }

    private Server server;
    private ServerConnector connector;

    public int getPort()
    {
        return connector.getLocalPort();
    }

    public void prepare(int port) throws FileNotFoundException
    {
        server = new Server();
        connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        String resourceBase = "src/test/resources/browser-debug-tool";
        Path basePath = Paths.get(resourceBase).toAbsolutePath();

        if (!Files.exists(basePath))
        {
            throw new FileNotFoundException("Base Path: " + basePath);
        }

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.setBaseResource(new PathResource(basePath));
        context.addServlet(DebugToolServlet.class, "/*");
        context.addServlet(DefaultServlet.class, "/");
        server.setHandler(context);

        LOG.info("{} setup on port {}", this.getClass().getName(), port);
    }

    public void start() throws Exception
    {
        server.setDumpAfterStart(Boolean.getBoolean("jetty.server.dumpAfterStart"));
        server.start();
        LOG.info("Server available on port {}", getPort());
    }

    public void stop() throws Exception
    {
        server.stop();
    }
}
