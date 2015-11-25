//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.proxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpParser;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpResponse;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ProxyServletFailureTest
{
    private static final String PROXIED_HEADER = "X-Proxied";

    @Parameterized.Parameters
    public static Iterable<Object[]> data()
    {
        return Arrays.asList(new Object[][]{
                {ProxyServlet.class},
                {AsyncProxyServlet.class}
        });
    }

    @Rule
    public final TestTracker tracker = new TestTracker();
    private HttpClient client;
    private Server proxy;
    private ServerConnector proxyConnector;
    private ProxyServlet proxyServlet;
    private Server server;
    private ServerConnector serverConnector;

    public ProxyServletFailureTest(Class<?> proxyServletClass) throws Exception
    {
        this.proxyServlet = (ProxyServlet)proxyServletClass.newInstance();
    }

    private void prepareProxy() throws Exception
    {
        prepareProxy(new HashMap<String, String>());
    }

    private void prepareProxy(Map<String, String> initParams) throws Exception
    {
        QueuedThreadPool executor = new QueuedThreadPool();
        executor.setName("proxy");
        proxy = new Server(executor);
        proxyConnector = new ServerConnector(proxy);
        proxy.addConnector(proxyConnector);
        proxyConnector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setDelayDispatchUntilContent(false);

        ServletContextHandler proxyCtx = new ServletContextHandler(proxy, "/", true, false);
        ServletHolder proxyServletHolder = new ServletHolder(proxyServlet);
        proxyServletHolder.setInitParameters(initParams);
        proxyCtx.addServlet(proxyServletHolder, "/*");

        proxy.start();

        client = prepareClient();
    }

    private HttpClient prepareClient() throws Exception
    {
        HttpClient result = new HttpClient();
        QueuedThreadPool executor = new QueuedThreadPool();
        executor.setName("client");
        result.setExecutor(executor);
        result.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyConnector.getLocalPort()));
        result.start();
        return result;
    }

    private void prepareServer(HttpServlet servlet) throws Exception
    {
        QueuedThreadPool executor = new QueuedThreadPool();
        executor.setName("server");
        server = new Server(executor);
        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);

        ServletContextHandler appCtx = new ServletContextHandler(server, "/", true, false);
        ServletHolder appServletHolder = new ServletHolder(servlet);
        appCtx.addServlet(appServletHolder, "/*");

        server.start();
    }

    @After
    public void disposeProxy() throws Exception
    {
        client.stop();
        proxy.stop();
    }

    @After
    public void disposeServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testClientRequestStallsHeadersProxyIdlesTimeout() throws Exception
    {
        prepareProxy();
        int idleTimeout = 2000;
        proxyConnector.setIdleTimeout(idleTimeout);

        prepareServer(new EchoHttpServlet());

        try (Socket socket = new Socket("localhost", proxyConnector.getLocalPort()))
        {
            String serverHostPort = "localhost:" + serverConnector.getLocalPort();
            String request = "" +
                    "GET http://" + serverHostPort + " HTTP/1.1\r\n" +
                    "Host: " + serverHostPort + "\r\n";
            // Don't sent the \r\n that would signal the end of the headers.
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Wait for idle timeout to fire.

            socket.setSoTimeout(2 * idleTimeout);
            InputStream input = socket.getInputStream();
            Assert.assertEquals(-1, input.read());
        }
    }

    @Test
    public void testClientRequestDoesNotSendContentProxyIdlesTimeout() throws Exception
    {
        prepareProxy();
        int idleTimeout = 2000;
        proxyConnector.setIdleTimeout(idleTimeout);

        prepareServer(new EchoHttpServlet());

        try (Socket socket = new Socket("localhost", proxyConnector.getLocalPort()))
        {
            String serverHostPort = "localhost:" + serverConnector.getLocalPort();
            String request = "" +
                    "GET http://" + serverHostPort + " HTTP/1.1\r\n" +
                    "Host: " + serverHostPort + "\r\n" +
                    "Content-Length: 1\r\n" +
                    "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Do not send the promised content, wait to idle timeout.

            socket.setSoTimeout(2 * idleTimeout);
            SimpleHttpParser parser = new SimpleHttpParser();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            SimpleHttpResponse response = parser.readResponse(reader);
            Assert.assertTrue(Integer.parseInt(response.getCode()) >= 500);
            String connectionHeader = response.getHeaders().get("connection");
            Assert.assertNotNull(connectionHeader);
            Assert.assertTrue(connectionHeader.contains("close"));
            Assert.assertEquals(-1, reader.read());
        }
    }

    @Test
    public void testClientRequestStallsContentProxyIdlesTimeout() throws Exception
    {
        prepareProxy();
        int idleTimeout = 2000;
        proxyConnector.setIdleTimeout(idleTimeout);

        prepareServer(new EchoHttpServlet());

        try (Socket socket = new Socket("localhost", proxyConnector.getLocalPort()))
        {
            String serverHostPort = "localhost:" + serverConnector.getLocalPort();
            String request = "" +
                    "GET http://" + serverHostPort + " HTTP/1.1\r\n" +
                    "Host: " + serverHostPort + "\r\n" +
                    "Content-Length: 2\r\n" +
                    "\r\n" +
                    "Z";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Do not send all the promised content, wait to idle timeout.

            socket.setSoTimeout(2 * idleTimeout);
            SimpleHttpParser parser = new SimpleHttpParser();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            SimpleHttpResponse response = parser.readResponse(reader);
            Assert.assertTrue(Integer.parseInt(response.getCode()) >= 500);
            String connectionHeader = response.getHeaders().get("connection");
            Assert.assertNotNull(connectionHeader);
            Assert.assertTrue(connectionHeader.contains("close"));
            Assert.assertEquals(-1, reader.read());
        }
    }

    @Test
    public void testProxyRequestStallsContentServerIdlesTimeout() throws Exception
    {
        final byte[] content = new byte[]{'C', '0', 'F', 'F', 'E', 'E'};
        if (proxyServlet instanceof AsyncProxyServlet)
        {
            proxyServlet = new AsyncProxyServlet()
            {
                @Override
                protected ContentProvider proxyRequestContent(HttpServletRequest request, HttpServletResponse response, Request proxyRequest) throws IOException
                {
                    DeferredContentProvider provider = new DeferredContentProvider()
                    {
                        @Override
                        public boolean offer(ByteBuffer buffer, Callback callback)
                        {
                            // Send less content to trigger the test condition.
                            buffer.limit(buffer.limit() - 1);
                            return super.offer(buffer.slice(), callback);
                        }
                    };
                    request.getInputStream().setReadListener(newReadListener(request, response, proxyRequest, provider));
                    return provider;
                }
            };
        }
        else
        {
            proxyServlet = new ProxyServlet()
            {
                @Override
                protected ContentProvider proxyRequestContent(HttpServletRequest request, HttpServletResponse response, Request proxyRequest) throws IOException
                {
                    return new BytesContentProvider(content)
                    {
                        @Override
                        public long getLength()
                        {
                            // Increase the content length to trigger the test condition.
                            return content.length + 1;
                        }
                    };
                }
            };
        }

        prepareProxy();
        prepareServer(new EchoHttpServlet());
        long idleTimeout = 1000;
        serverConnector.setIdleTimeout(idleTimeout);

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .content(new BytesContentProvider(content))
                .send();

        Assert.assertEquals(500, response.getStatus());
    }

    @Test(expected = TimeoutException.class)
    public void testClientRequestExpires() throws Exception
    {
        prepareProxy();
        final long timeout = 1000;
        proxyServlet.setTimeout(3 * timeout);
        prepareServer(new HttpServlet()
        {
            @Override
            protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                try
                {
                    TimeUnit.MILLISECONDS.sleep(2 * timeout);
                }
                catch (InterruptedException x)
                {
                    throw new ServletException(x);
                }
            }
        });

        client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(timeout, TimeUnit.MILLISECONDS)
                .send();
        Assert.fail();
    }

    @Test
    public void testProxyRequestExpired() throws Exception
    {
        prepareProxy();
        final long timeout = 1000;
        proxyServlet.setTimeout(timeout);
        prepareServer(new HttpServlet()
        {
            @Override
            protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                if (request.getHeader("Via") != null)
                    response.addHeader(PROXIED_HEADER, "true");
                try
                {
                    TimeUnit.MILLISECONDS.sleep(2 * timeout);
                }
                catch (InterruptedException x)
                {
                    throw new ServletException(x);
                }
            }
        });

        Response response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(3 * timeout, TimeUnit.MILLISECONDS)
                .send();
        Assert.assertEquals(504, response.getStatus());
        Assert.assertFalse(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Test
    public void testServerDown() throws Exception
    {
        prepareProxy();
        prepareServer(new EmptyHttpServlet());

        // Shutdown the server
        int serverPort = serverConnector.getLocalPort();
        server.stop();

        ContentResponse response = client.newRequest("localhost", serverPort)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(502, response.getStatus());
    }

    @Test
    public void testServerException() throws Exception
    {
        ((StdErrLog)Log.getLogger(ServletHandler.class)).setHideStacks(true);
        try
        {
            prepareProxy();
            prepareServer(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
                {
                    throw new ServletException("Expected Test Exception");
                }
            });

            ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();

            Assert.assertEquals(500, response.getStatus());
        }
        finally
        {
            ((StdErrLog)Log.getLogger(ServletHandler.class)).setHideStacks(false);
        }
    }
}
