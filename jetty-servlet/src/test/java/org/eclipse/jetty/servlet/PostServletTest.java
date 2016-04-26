//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlet;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class PostServletTest
{
    private static final Logger LOG = Log.getLogger(PostServletTest.class);

    public static class BasicReadPostServlet extends HttpServlet
    {
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
        {
            try
            {
                request.getInputStream().read();
            }
            catch (IOException e0)
            {
                try
                {
                    // this read-call should fail immediately
                    request.getInputStream().read();
                }
                catch (IOException e1)
                {
                    LOG.warn(e1);
                }
            }
        }
    }

    private Server server;
    private LocalConnector connector;

    @Before
    public void startServer() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(BasicReadPostServlet.class, "/post");

        server.setHandler(context);

        server.start();
    }

    @After
    public void stopServer() throws Exception
    {
        this.server.stop();
    }

    @Test
    public void testBadPost() throws Exception
    {
        StringBuilder req = new StringBuilder();
        req.append("POST /post HTTP/1.1\r\n");
        req.append("Host: localhost\r\n");
        req.append("Connection: close\r\n");
        req.append("Transfer-Encoding: chunked\r\n");
        req.append("\r\n");
        req.append("x\r\n"); // intentionally bad (not a valid chunked char here)
        req.append("\r\n");

        String resp = connector.getResponses(req.toString());

        TimeUnit.MINUTES.sleep(4);

        assertThat("resp", resp, containsString("HTTP/1.1 400 Bad Request"));
    }
}
