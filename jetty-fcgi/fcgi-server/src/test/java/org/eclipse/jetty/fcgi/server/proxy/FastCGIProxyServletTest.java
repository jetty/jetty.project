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

package org.eclipse.jetty.fcgi.server.proxy;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.Callback;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class FastCGIProxyServletTest
{
    @Parameterized.Parameters
    public static Collection<Object[]> parameters()
    {
        return Arrays.asList(new Object[]{true}, new Object[]{false});
    }

    private final boolean sendStatus200;
    private Server server;
    private ServerConnector httpConnector;
    private ServerConnector fcgiConnector;
    private HttpClient client;

    public FastCGIProxyServletTest(boolean sendStatus200)
    {
        this.sendStatus200 = sendStatus200;
    }

    public void prepare(HttpServlet servlet) throws Exception
    {
        server = new Server();
        httpConnector = new ServerConnector(server);
        server.addConnector(httpConnector);

        fcgiConnector = new ServerConnector(server, new ServerFCGIConnectionFactory(new HttpConfiguration(), sendStatus200));
        server.addConnector(fcgiConnector);

        final String contextPath = "/";
        ServletContextHandler context = new ServletContextHandler(server, contextPath);

        final String servletPath = "/script";
        FastCGIProxyServlet fcgiServlet = new FastCGIProxyServlet()
        {
            @Override
            protected URI rewriteURI(HttpServletRequest request)
            {
                return URI.create("http://localhost:" + fcgiConnector.getLocalPort() + servletPath + request.getServletPath());
            }
        };
        ServletHolder fcgiServletHolder = new ServletHolder(fcgiServlet);
        context.addServlet(fcgiServletHolder, "*.php");
        fcgiServletHolder.setInitParameter(FastCGIProxyServlet.SCRIPT_ROOT_INIT_PARAM, "/scriptRoot");
        fcgiServletHolder.setInitParameter("proxyTo", "http://localhost");
        fcgiServletHolder.setInitParameter(FastCGIProxyServlet.SCRIPT_PATTERN_INIT_PARAM, "(.+?\\.php)");

        context.addServlet(new ServletHolder(servlet), servletPath + "/*");

        client = new HttpClient();
        server.addBean(client);

        server.start();
    }

    @After
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGETWithSmallResponseContent() throws Exception
    {
        testGETWithResponseContent(1024, 0);
    }

    @Test
    public void testGETWithLargeResponseContent() throws Exception
    {
        testGETWithResponseContent(16 * 1024 * 1024, 0);
    }

    @Test
    public void testGETWithLargeResponseContentWithSlowClient() throws Exception
    {
        testGETWithResponseContent(16 * 1024 * 1024, 1);
    }

    private void testGETWithResponseContent(int length, final long delay) throws Exception
    {
        final byte[] data = new byte[length];
        new Random().nextBytes(data);

        final String path = "/foo/index.php";
        prepare(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                Assert.assertTrue(req.getRequestURI().endsWith(path));
                resp.setContentLength(data.length);
                resp.getOutputStream().write(data);
            }
        });

        Request request = client.newRequest("localhost", httpConnector.getLocalPort())
                .onResponseContentAsync(new Response.AsyncContentListener()
                {
                    @Override
                    public void onContent(Response response, ByteBuffer content, Callback callback)
                    {
                        try
                        {
                            if (delay > 0)
                                TimeUnit.MILLISECONDS.sleep(delay);
                            callback.succeeded();
                        }
                        catch (InterruptedException x)
                        {
                            callback.failed(x);
                        }
                    }
                })
                .path(path);
        FutureResponseListener listener = new FutureResponseListener(request, length);
        request.send(listener);

        ContentResponse response = listener.get(30, TimeUnit.SECONDS);

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(data, response.getContent());
    }
}
