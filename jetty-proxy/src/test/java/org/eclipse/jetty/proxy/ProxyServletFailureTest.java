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

package org.eclipse.jetty.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
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
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ProxyServletFailureTest
{
    private static final String PROXIED_HEADER = "X-Proxied";

    public static Stream<Arguments> impls()
    {
        return Stream.of(
            ProxyServlet.class,
            AsyncProxyServlet.class
        ).map(Arguments::of);
    }

    private HttpClient client;
    private Server proxy;
    private ServerConnector proxyConnector;
    private ProxyServlet proxyServlet;
    private Server server;
    private ServerConnector serverConnector;

    private void prepareProxy(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        prepareProxy(proxyServletClass, new HashMap<>());
    }

    private void prepareProxy(Class<? extends ProxyServlet> proxyServletClass, Map<String, String> initParams) throws Exception
    {
        proxyServlet = proxyServletClass.getDeclaredConstructor().newInstance();
        prepareProxy(proxyServlet, initParams);
    }

    private void prepareProxy(ProxyServlet proxyServlet, Map<String, String> initParams) throws Exception
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

    @AfterEach
    public void disposeProxy() throws Exception
    {
        client.stop();
        proxy.stop();
    }

    @AfterEach
    public void disposeServer() throws Exception
    {
        server.stop();
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testClientRequestStallsHeadersProxyIdlesTimeout(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        prepareProxy(proxyServletClass);
        int idleTimeout = 2000;
        proxyConnector.setIdleTimeout(idleTimeout);

        prepareServer(new EchoHttpServlet());

        try (Socket socket = new Socket("localhost", proxyConnector.getLocalPort()))
        {
            String serverHostPort = "localhost:" + serverConnector.getLocalPort();
            String request =
                "GET http://" + serverHostPort + " HTTP/1.1\r\n" +
                    "Host: " + serverHostPort + "\r\n";
            // Don't sent the \r\n that would signal the end of the headers.
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Wait for idle timeout to fire.

            socket.setSoTimeout(2 * idleTimeout);
            InputStream input = socket.getInputStream();
            assertEquals(-1, input.read());
        }
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testClientRequestDoesNotSendContentProxyIdlesTimeout(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        prepareProxy(proxyServletClass);
        int idleTimeout = 2000;
        proxyConnector.setIdleTimeout(idleTimeout);

        prepareServer(new EchoHttpServlet());

        try (Socket socket = new Socket("localhost", proxyConnector.getLocalPort()))
        {
            String serverHostPort = "localhost:" + serverConnector.getLocalPort();
            String request =
                "GET http://" + serverHostPort + " HTTP/1.1\r\n" +
                    "Host: " + serverHostPort + "\r\n" +
                    "Content-Length: 1\r\n" +
                    "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Do not send the promised content, wait to idle timeout.

            socket.setSoTimeout(2 * idleTimeout);

            HttpTester.Response response = HttpTester.parseResponse(socket.getInputStream());
            assertThat("response status", response.getStatus(), greaterThanOrEqualTo(500));
            String connectionHeader = response.get(HttpHeader.CONNECTION);
            assertNotNull(connectionHeader);
            assertThat(connectionHeader, containsString("close"));
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testClientRequestStallsContentProxyIdlesTimeout(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        prepareProxy(proxyServletClass);
        int idleTimeout = 2000;
        proxyConnector.setIdleTimeout(idleTimeout);

        prepareServer(new EchoHttpServlet());

        try (Socket socket = new Socket("localhost", proxyConnector.getLocalPort()))
        {
            String serverHostPort = "localhost:" + serverConnector.getLocalPort();
            String request =
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

            HttpTester.Response response = HttpTester.parseResponse(socket.getInputStream());
            assertThat("response status", response.getStatus(), greaterThanOrEqualTo(500));
            String connectionHeader = response.get(HttpHeader.CONNECTION);
            assertNotNull(connectionHeader);
            assertThat(connectionHeader, containsString("close"));
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyRequestStallsContentServerIdlesTimeout(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final byte[] content = new byte[]{'C', '0', 'F', 'F', 'E', 'E'};
        int expected;
        ProxyServlet proxyServlet = null;

        if (proxyServletClass.isAssignableFrom(AsyncProxyServlet.class))
        {
            // TODO should this be a 502 also???
            expected = 500;
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
            expected = 502;
            proxyServlet = new ProxyServlet()
            {
                @Override
                protected ContentProvider proxyRequestContent(HttpServletRequest request, HttpServletResponse response, Request proxyRequest)
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

        prepareProxy(proxyServlet, new HashMap<>());
        prepareServer(new EchoHttpServlet());
        long idleTimeout = 1000;
        serverConnector.setIdleTimeout(idleTimeout);

        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .content(new BytesContentProvider(content))
                .send();

            assertThat(response.toString(), response.getStatus(), is(expected));
        }
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testClientRequestExpires(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        prepareProxy(proxyServletClass);
        final long timeout = 1000;
        proxyServlet.setTimeout(3 * timeout);
        prepareServer(new HttpServlet()
        {
            @Override
            protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException
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

        assertThrows(TimeoutException.class, () ->
            client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(timeout, TimeUnit.MILLISECONDS)
                .send());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyRequestExpired(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        prepareProxy(proxyServletClass);
        final long timeout = 1000;
        proxyServlet.setTimeout(timeout);
        prepareServer(new HttpServlet()
        {
            @Override
            protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException
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
        assertEquals(504, response.getStatus());
        assertFalse(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testServerDown(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        prepareProxy(proxyServletClass);
        prepareServer(new EmptyHttpServlet());

        // Shutdown the server
        int serverPort = serverConnector.getLocalPort();
        server.stop();

        ContentResponse response = client.newRequest("localhost", serverPort)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(502, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testServerException(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            prepareProxy(proxyServletClass);
            prepareServer(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException
                {
                    throw new ServletException("Expected Test Exception");
                }
            });

            ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(500, response.getStatus());
        }
    }
}
