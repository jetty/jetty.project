//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket;

import static org.hamcrest.Matchers.*;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.helper.CaptureSocket;
import org.eclipse.jetty.websocket.helper.SafariD00;
import org.eclipse.jetty.websocket.helper.WebSocketCaptureServlet;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class WebSocketMinVersionTest
{
    private Server server;
    private WebSocketCaptureServlet servlet;
    private URI serverUri;

    @BeforeClass
    public static void initLogging()
    {
        // Configure Logging
        // System.setProperty("org.eclipse.jetty.util.log.class",StdErrLog.class.getName());
        System.setProperty("org.eclipse.jetty.websocket.helper.LEVEL","DEBUG");
    }

    @Before
    public void startServer() throws Exception
    {
        // Configure Server
        server = new Server(0);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        // Serve capture servlet
        servlet = new WebSocketCaptureServlet();
        ServletHolder holder = new ServletHolder(servlet);
        holder.setInitParameter("minVersion","8");
        context.addServlet(holder,"/");

        // Start Server
        server.start();

        Connector conn = server.getConnectors()[0];
        String host = conn.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = conn.getLocalPort();
        serverUri = new URI(String.format("ws://%s:%d/",host,port));
        // System.out.printf("Server URI: %s%n",serverUri);
    }

    @Test
    public void testAttemptUpgrade() throws Exception
    {
        SafariD00 safari = new SafariD00(serverUri);

        try
        {
            safari.connect();
            safari.issueHandshake();
            Assert.fail("Expected upgrade failure");
        }
        catch(IllegalStateException e) {
            String respHeader = e.getMessage();
            Assert.assertThat("Response Header", respHeader, containsString("HTTP/1.1 400 Unsupported websocket version specification"));
        }
        finally
        {
            // System.out.println("Closing client socket");
            safari.disconnect();
        }
    }

    public static void threadSleep(int dur, TimeUnit unit) throws InterruptedException
    {
        long ms = TimeUnit.MILLISECONDS.convert(dur,unit);
        Thread.sleep(ms);
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }
}
