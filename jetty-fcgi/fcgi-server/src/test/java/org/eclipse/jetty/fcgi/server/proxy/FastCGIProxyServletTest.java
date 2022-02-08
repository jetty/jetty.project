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

package org.eclipse.jetty.fcgi.server.proxy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
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
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.unixdomain.server.UnixDomainServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FastCGIProxyServletTest
{
    private final Map<String, String> fcgiParams = new HashMap<>();
    private Server server;
    private ServerConnector httpConnector;
    private Connector fcgiConnector;
    private ServletContextHandler context;
    private HttpClient client;
    private Path unixDomainPath;

    public void prepare(boolean sendStatus200, HttpServlet servlet) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        httpConnector = new ServerConnector(server);
        server.addConnector(httpConnector);

        ServerFCGIConnectionFactory fcgi = new ServerFCGIConnectionFactory(new HttpConfiguration(), sendStatus200);
        if (unixDomainPath == null)
        {
            fcgiConnector = new ServerConnector(server, fcgi);
        }
        else
        {
            UnixDomainServerConnector connector = new UnixDomainServerConnector(server, fcgi);
            connector.setUnixDomainPath(unixDomainPath);
            fcgiConnector = connector;
        }
        server.addConnector(fcgiConnector);

        String contextPath = "/";
        context = new ServletContextHandler(server, contextPath);

        String servletPath = "/script";
        FastCGIProxyServlet fcgiServlet = new FastCGIProxyServlet()
        {
            @Override
            protected String rewriteTarget(HttpServletRequest request)
            {
                String uri = "http://localhost";
                if (unixDomainPath == null)
                    uri += ":" + ((ServerConnector)fcgiConnector).getLocalPort();
                return uri + servletPath + request.getServletPath();
            }
        };
        ServletHolder fcgiServletHolder = new ServletHolder(fcgiServlet);
        fcgiServletHolder.setName("fcgi");
        fcgiServletHolder.setInitParameter(FastCGIProxyServlet.SCRIPT_ROOT_INIT_PARAM, "/scriptRoot");
        fcgiServletHolder.setInitParameter("proxyTo", "http://localhost");
        fcgiServletHolder.setInitParameter(FastCGIProxyServlet.SCRIPT_PATTERN_INIT_PARAM, "(.+?\\.php)");
        fcgiParams.forEach(fcgiServletHolder::setInitParameter);
        context.addServlet(fcgiServletHolder, "*.php");

        context.addServlet(new ServletHolder(servlet), servletPath + "/*");

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient();
        client.setExecutor(clientThreads);
        server.addBean(client);

        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        server.stop();
    }

    @ParameterizedTest(name = "[{index}] sendStatus200={0}")
    @ValueSource(booleans = {true, false})
    public void testGETWithSmallResponseContent(boolean sendStatus200) throws Exception
    {
        testGETWithResponseContent(sendStatus200, 1024, 0);
    }

    @ParameterizedTest(name = "[{index}] sendStatus200={0}")
    @ValueSource(booleans = {true, false})
    public void testGETWithLargeResponseContent(boolean sendStatus200) throws Exception
    {
        testGETWithResponseContent(sendStatus200, 16 * 1024 * 1024, 0);
    }

    @ParameterizedTest(name = "[{index}] sendStatus200={0}")
    @ValueSource(booleans = {true, false})
    public void testGETWithLargeResponseContentWithSlowClient(boolean sendStatus200) throws Exception
    {
        testGETWithResponseContent(sendStatus200, 16 * 1024 * 1024, 1);
    }

    private void testGETWithResponseContent(boolean sendStatus200, int length, long delay) throws Exception
    {
        byte[] data = new byte[length];
        new Random().nextBytes(data);

        String path = "/foo/index.php";
        prepare(sendStatus200, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                assertTrue(request.getRequestURI().endsWith(path));
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

        assertEquals(200, response.getStatus());
        assertArrayEquals(data, response.getContent());
    }

    @ParameterizedTest(name = "[{index}] sendStatus200={0}")
    @ValueSource(booleans = {true, false})
    public void testURIRewrite(boolean sendStatus200) throws Exception
    {
        String originalPath = "/original/index.php";
        String originalQuery = "foo=bar";
        String remotePath = "/remote/index.php";
        String pathAttribute = "_path_attribute";
        String queryAttribute = "_query_attribute";
        fcgiParams.put(FastCGIProxyServlet.ORIGINAL_URI_ATTRIBUTE_INIT_PARAM, pathAttribute);
        fcgiParams.put(FastCGIProxyServlet.ORIGINAL_QUERY_ATTRIBUTE_INIT_PARAM, queryAttribute);
        prepare(sendStatus200, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                assertThat((String)request.getAttribute(FCGI.Headers.REQUEST_URI), Matchers.startsWith(originalPath));
                assertEquals(originalQuery, request.getAttribute(FCGI.Headers.QUERY_STRING));
                assertThat(request.getRequestURI(), Matchers.endsWith(remotePath));
            }
        });
        context.stop();
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

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_16)
    public void testUnixDomain() throws Exception
    {
        int maxUnixDomainPathLength = 108;
        Path path = Files.createTempFile("unix", ".sock");
        if (path.normalize().toAbsolutePath().toString().length() > maxUnixDomainPathLength)
            path = Files.createTempFile(Path.of("/tmp"), "unix", ".sock");
        assertTrue(Files.deleteIfExists(path));
        unixDomainPath = path;
        fcgiParams.put("unixDomainPath", path.toString());
        byte[] content = new byte[512];
        new Random().nextBytes(content);
        prepare(true, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.getOutputStream().write(content);
            }
        });

        ContentResponse response = client.newRequest("localhost", httpConnector.getLocalPort())
            .path("/index.php")
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertArrayEquals(content, response.getContent());
    }
}
