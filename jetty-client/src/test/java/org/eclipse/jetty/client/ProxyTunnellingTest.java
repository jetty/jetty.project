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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.Ignore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProxyTunnellingTest
{
    private Server server;
    private Connector serverConnector;
    private Server proxy;
    private Connector proxyConnector;
    private int serverConnectTimeout = 1000;

    protected int proxyPort()
    {
        return proxyConnector.getLocalPort();
    }

    protected void startSSLServer(Handler handler) throws Exception
    {
        SslSelectChannelConnector connector = new SslSelectChannelConnector();
        String keyStorePath = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();
        SslContextFactory cf = connector.getSslContextFactory();
        cf.setKeyStorePath(keyStorePath);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        startServer(connector, handler);
    }

    protected void startServer(Connector connector, Handler handler) throws Exception
    {
        server = new Server();
        serverConnector = connector;
        server.addConnector(serverConnector);
        server.setHandler(handler);
        server.start();
    }

    protected void startProxy() throws Exception
    {
        proxy = new Server();
        proxyConnector = new SelectChannelConnector();
        proxy.addConnector(proxyConnector);
        ConnectHandler connectHandler = new ConnectHandler();
        // Under Windows, it takes a while to detect that a connection
        // attempt fails, so use an explicit timeout
        connectHandler.setConnectTimeout(serverConnectTimeout);
        proxy.setHandler(connectHandler);
        proxy.start();
    }

    @After
    public void stop() throws Exception
    {
        stopProxy();
        stopServer();
    }

    protected void stopServer() throws Exception
    {
        if (server != null)
        {
            server.stop();
            server.join();
        }
    }

    protected void stopProxy() throws Exception
    {
        if (proxy != null)
        {
            proxy.stop();
            proxy.join();
        }
    }

    @Test
    public void testOneExchangeViaSSL() throws Exception
    {
        startSSLServer(new ServerHandler());
        startProxy();

        HttpClient httpClient = new HttpClient();
        httpClient.setProxy(new Address("localhost", proxyPort()));
        httpClient.start();

        try
        {
            ContentExchange exchange = new ContentExchange(true);
            exchange.setMethod(HttpMethods.GET);
            String body = "BODY";
            exchange.setURL("https://localhost:" + serverConnector.getLocalPort() + "/echo?body=" + URLEncoder.encode(body, "UTF-8"));

            httpClient.send(exchange);
            assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
            String content = exchange.getResponseContent();
            assertEquals(body, content);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testTwoExchangesViaSSL() throws Exception
    {
        startSSLServer(new ServerHandler());
        startProxy();

        HttpClient httpClient = new HttpClient();
        httpClient.setProxy(new Address("localhost", proxyPort()));
        httpClient.start();

        try
        {
            ContentExchange exchange = new ContentExchange(true);
            exchange.setMethod(HttpMethods.GET);
            String body = "BODY";
            exchange.setURL("https://localhost:" + serverConnector.getLocalPort() + "/echo?body=" + URLEncoder.encode(body, "UTF-8"));

            httpClient.send(exchange);
            assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
            String content = exchange.getResponseContent();
            assertEquals(body, content);

            exchange = new ContentExchange(true);
            exchange.setMethod(HttpMethods.POST);
            exchange.setURL("https://localhost:" + serverConnector.getLocalPort() + "/echo");
            exchange.setRequestHeader(HttpHeaders.CONTENT_TYPE, MimeTypes.FORM_ENCODED);
            content = "body=" + body;
            exchange.setRequestHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length()));
            exchange.setRequestContent(new ByteArrayBuffer(content, "UTF-8"));

            httpClient.send(exchange);
            assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
            content = exchange.getResponseContent();
            assertEquals(body, content);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testTwoConcurrentExchangesViaSSL() throws Exception
    {
        startSSLServer(new ServerHandler());
        startProxy();

        final HttpClient httpClient = new HttpClient();
        httpClient.setProxy(new Address("localhost", proxyPort()));
        httpClient.start();

        try
        {
            final AtomicReference<AbstractHttpConnection> connection = new AtomicReference<AbstractHttpConnection>();
            final CountDownLatch connectionLatch = new CountDownLatch(1);
            ContentExchange exchange1 = new ContentExchange(true)
            {
                @Override
                protected void onRequestCommitted() throws IOException
                {
                    // Simulate the concurrent send of a second exchange which
                    // triggers the opening of a second connection but then
                    // it's "stolen" by the first connection, so that the
                    // second connection is put into the idle connections.

                    HttpDestination destination = httpClient.getDestination(new Address("localhost", serverConnector.getLocalPort()), true);
                    destination.startNewConnection();

                    // Wait until we have the new connection
                    AbstractHttpConnection httpConnection = null;
                    while (httpConnection == null)
                    {
                        try
                        {
                            Thread.sleep(10);
                            httpConnection = destination.getIdleConnection();
                        }
                        catch (InterruptedException x)
                        {
                            throw new InterruptedIOException();
                        }
                    }

                    connection.set(httpConnection);
                    connectionLatch.countDown();
                }
            };
            exchange1.setMethod(HttpMethods.GET);
            String body1 = "BODY";
            exchange1.setURL("https://localhost:" + serverConnector.getLocalPort() + "/echo?body=" + URLEncoder.encode(body1, "UTF-8"));

            httpClient.send(exchange1);
            assertEquals(HttpExchange.STATUS_COMPLETED, exchange1.waitForDone());
            assertEquals(HttpStatus.OK_200, exchange1.getResponseStatus());
            String content1 = exchange1.getResponseContent();
            assertEquals(body1, content1);

            Assert.assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));

            ContentExchange exchange2 = new ContentExchange(true);
            exchange2.setMethod(HttpMethods.POST);
            exchange2.setURL("https://localhost:" + serverConnector.getLocalPort() + "/echo");
            exchange2.setRequestHeader(HttpHeaders.CONTENT_TYPE, MimeTypes.FORM_ENCODED);
            String body2 = "body=" + body1;
            exchange2.setRequestHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(body2.length()));
            exchange2.setRequestContent(new ByteArrayBuffer(body2, "UTF-8"));

            // Make sure the second connection can send the exchange via the tunnel
            connection.get().send(exchange2);
            assertEquals(HttpExchange.STATUS_COMPLETED, exchange2.waitForDone());
            assertEquals(HttpStatus.OK_200, exchange2.getResponseStatus());
            String content2 = exchange2.getResponseContent();
            assertEquals(body1, content2);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testProxyDown() throws Exception
    {
        startSSLServer(new ServerHandler());
        startProxy();
        int proxyPort = proxyPort();
        stopProxy();

        HttpClient httpClient = new HttpClient();
        httpClient.setProxy(new Address("localhost", proxyPort));
        httpClient.start();

        try
        {
            final CountDownLatch latch = new CountDownLatch(1);
            ContentExchange exchange = new ContentExchange(true)
            {
                @Override
                protected void onConnectionFailed(Throwable x)
                {
                    latch.countDown();
                }
            };
            exchange.setMethod(HttpMethods.GET);
            String body = "BODY";
            exchange.setURL("https://localhost:" + serverConnector.getLocalPort() + "/echo?body=" + URLEncoder.encode(body, "UTF-8"));

            httpClient.send(exchange);
            assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testServerDown() throws Exception
    {
        startSSLServer(new ServerHandler());
        int serverPort = serverConnector.getLocalPort();
        stopServer();
        startProxy();

        HttpClient httpClient = new HttpClient();
        httpClient.setProxy(new Address("localhost", proxyPort()));
        httpClient.start();

        try
        {
            final CountDownLatch latch = new CountDownLatch(1);
            ContentExchange exchange = new ContentExchange(true)
            {
                @Override
                protected void onException(Throwable x)
                {
                    latch.countDown();
                }

            };
            exchange.setMethod(HttpMethods.GET);
            String body = "BODY";
            exchange.setURL("https://localhost:" + serverPort + "/echo?body=" + URLEncoder.encode(body, "UTF-8"));

            httpClient.send(exchange);
            assertTrue("Server connect exception should have occurred", latch.await(serverConnectTimeout * 2, TimeUnit.MILLISECONDS));
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    @Ignore
    public void testExternalProxy() throws Exception
    {
        // Free proxy server obtained from http://hidemyass.com/proxy-list/
        String proxyHost = "81.208.25.53";
        int proxyPort = 3128;
        try
        {
            new Socket(proxyHost, proxyPort).close();
        }
        catch (IOException x)
        {
            Assume.assumeNoException(x);
        }

        // Start the server to start the SslContextFactory
        startSSLServer(new AbstractHandler()
        {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                throw new ServletException();
            }
        });

        HttpClient httpClient = new HttpClient();
        httpClient.setProxy(new Address(proxyHost, proxyPort));
        httpClient.registerListener(RedirectListener.class.getName());
        httpClient.start();

        try
        {
            ContentExchange exchange = new ContentExchange(true);
            // Use a longer timeout, sometimes the proxy takes a while to answer
            exchange.setTimeout(20000);
            exchange.setURL("https://www.google.com");
            httpClient.send(exchange);
            assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
            assertEquals(200, exchange.getResponseStatus());
        }
        finally
        {
            httpClient.stop();
        }
    }

    private static class ServerHandler extends AbstractHandler
    {
        public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            request.setHandled(true);

            String uri = httpRequest.getRequestURI();
            if ("/echo".equals(uri))
            {
                String body = httpRequest.getParameter("body");
                ServletOutputStream output = httpResponse.getOutputStream();
                output.print(body);
            }
            else
            {
                throw new ServletException();
            }
        }
    }
}
