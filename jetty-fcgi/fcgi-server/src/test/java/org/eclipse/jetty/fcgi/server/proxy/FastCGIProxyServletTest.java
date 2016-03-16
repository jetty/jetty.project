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

package org.eclipse.jetty.fcgi.server.proxy;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class FastCGIProxyServletTest
{
    @Parameterized.Parameters
    public static Object[] parameters()
    {
        return new Object[]{true, false};
    }

    private final boolean sendStatus200;
    private Server server;
    private ServerConnector httpConnector;
    private ServerConnector fcgiConnector;
    private ServletContextHandler context;
    private HttpClient client;

    public FastCGIProxyServletTest(boolean sendStatus200)
    {
        this.sendStatus200 = sendStatus200;
    }

    public void prepare(HttpServlet servlet) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        httpConnector = new ServerConnector(server);
        server.addConnector(httpConnector);

        fcgiConnector = new ServerConnector(server, new ServerFCGIConnectionFactory(new HttpConfiguration(), sendStatus200));
        server.addConnector(fcgiConnector);

        final String contextPath = "/";
        context = new ServletContextHandler(server, contextPath);

        final String servletPath = "/script";
        FastCGIProxyServlet fcgiServlet = new FastCGIProxyServlet()
        {
            @Override
            protected String rewriteTarget(HttpServletRequest request)
            {
                return "http://localhost:" + fcgiConnector.getLocalPort() + servletPath + request.getServletPath();
            }
        };
        ServletHolder fcgiServletHolder = new ServletHolder(fcgiServlet);
        fcgiServletHolder.setName("fcgi");
        fcgiServletHolder.setInitParameter(FastCGIProxyServlet.SCRIPT_ROOT_INIT_PARAM, "/scriptRoot");
        fcgiServletHolder.setInitParameter("proxyTo", "http://localhost");
        fcgiServletHolder.setInitParameter(FastCGIProxyServlet.SCRIPT_PATTERN_INIT_PARAM, "(.+?\\.php)");
        context.addServlet(fcgiServletHolder, "*.php");

        context.addServlet(new ServletHolder(servlet), servletPath + "/*");

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient();
        client.setExecutor(clientThreads);
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
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Assert.assertTrue(request.getRequestURI().endsWith(path));
                response.setContentLength(data.length);
                response.getOutputStream().write(data);
            }
        });

        Request request = client.newRequest("localhost", httpConnector.getLocalPort())
                .onResponseContentAsync((response, content, callback) ->
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
                })
                .path(path);
        FutureResponseListener listener = new FutureResponseListener(request, length);
        request.send(listener);

        ContentResponse response = listener.get(30, TimeUnit.SECONDS);

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(data, response.getContent());
    }

    @Test
    public void testURIRewrite() throws Exception
    {
        String originalPath = "/original/index.php";
        String originalQuery = "foo=bar";
        String remotePath = "/remote/index.php";
        prepare(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Assert.assertThat((String)request.getAttribute(FCGI.Headers.REQUEST_URI), Matchers.startsWith(originalPath));
                Assert.assertEquals(originalQuery, request.getAttribute(FCGI.Headers.QUERY_STRING));
                Assert.assertThat(request.getRequestURI(), Matchers.endsWith(remotePath));
            }
        });
        context.stop();
        String pathAttribute = "_path_attribute";
        String queryAttribute = "_query_attribute";
        ServletHolder fcgi = context.getServletHandler().getServlet("fcgi");
        fcgi.setInitParameter(FastCGIProxyServlet.ORIGINAL_URI_ATTRIBUTE_INIT_PARAM, pathAttribute);
        fcgi.setInitParameter(FastCGIProxyServlet.ORIGINAL_QUERY_ATTRIBUTE_INIT_PARAM, queryAttribute);
        context.insertHandler(new HandlerWrapper()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                if (target.startsWith("/remote/"))
                {
                    request.setAttribute(pathAttribute, originalPath);
                    request.setAttribute(queryAttribute, originalQuery);
                }
                super.handle(target, baseRequest, request, response);
            }
        });
        context.start();

        ContentResponse response = client.newRequest("localhost", httpConnector.getLocalPort())
                .path(remotePath)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }
}
