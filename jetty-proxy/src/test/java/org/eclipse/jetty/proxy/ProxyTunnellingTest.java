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

package org.eclipse.jetty.proxy;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class ProxyTunnellingTest
{
    @Rule
    public TestTracker tracker = new TestTracker();

    private SslContextFactory sslContextFactory;
    private Server server;
    private ServerConnector serverConnector;
    private Server proxy;
    private ServerConnector proxyConnector;

    protected int proxyPort()
    {
        return proxyConnector.getLocalPort();
    }

    protected void startSSLServer(Handler handler) throws Exception
    {
        sslContextFactory = new SslContextFactory();
        String keyStorePath = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();
        sslContextFactory.setKeyStorePath(keyStorePath);
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");
        server = new Server();
        serverConnector = new ServerConnector(server, sslContextFactory);
        server.addConnector(serverConnector);
        server.setHandler(handler);
        server.start();
    }

    protected void startProxy() throws Exception
    {
        startProxy(new ConnectHandler());
    }

    protected void startProxy(ConnectHandler connectHandler) throws Exception
    {
        proxy = new Server();
        proxyConnector = new ServerConnector(proxy);
        proxy.addConnector(proxyConnector);
        // Under Windows, it takes a while to detect that a connection
        // attempt fails, so use an explicit timeout
        connectHandler.setConnectTimeout(1000);
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

        HttpClient httpClient = new HttpClient(sslContextFactory);
        httpClient.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyPort()));
        httpClient.start();

        try
        {
            String body = "BODY";
            ContentResponse response = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .method(HttpMethod.GET)
                    .path("/echo?body=" + URLEncoder.encode(body, "UTF-8"))
                    .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = response.getContentAsString();
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

        HttpClient httpClient = new HttpClient(sslContextFactory);
        httpClient.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyPort()));
        httpClient.start();

        try
        {
            String body = "BODY";
            ContentResponse response1 = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .method(HttpMethod.GET)
                    .path("/echo?body=" + URLEncoder.encode(body, "UTF-8"))
                    .send();

            assertEquals(HttpStatus.OK_200, response1.getStatus());
            String content = response1.getContentAsString();
            assertEquals(body, content);

            content = "body=" + body;
            ContentResponse response2 = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .method(HttpMethod.POST)
                    .path("/echo")
                    .header(HttpHeader.CONTENT_TYPE, MimeTypes.Type.FORM_ENCODED.asString())
                    .header(HttpHeader.CONTENT_LENGTH, String.valueOf(content.length()))
                    .content(new StringContentProvider(content))
                    .send();

            assertEquals(HttpStatus.OK_200, response2.getStatus());
            content = response2.getContentAsString();
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

        final HttpClient httpClient = new HttpClient(sslContextFactory);
        httpClient.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyPort()));
        httpClient.start();

        try
        {
            final AtomicReference<Connection> connection = new AtomicReference<>();
            final CountDownLatch connectionLatch = new CountDownLatch(1);
            String body1 = "BODY";
            ContentResponse response1 = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .method(HttpMethod.GET)
                    .path("/echo?body=" + URLEncoder.encode(body1, "UTF-8"))
                    .onRequestCommit(new org.eclipse.jetty.client.api.Request.CommitListener()
                    {
                        @Override
                        public void onCommit(org.eclipse.jetty.client.api.Request request)
                        {
                            Destination destination = httpClient.getDestination(HttpScheme.HTTPS.asString(), "localhost", serverConnector.getLocalPort());
                            destination.newConnection(new Promise.Adapter<Connection>()
                            {
                                @Override
                                public void succeeded(Connection result)
                                {
                                    connection.set(result);
                                    connectionLatch.countDown();
                                }
                            });
                        }
                    })
                    .send();

            assertEquals(HttpStatus.OK_200, response1.getStatus());
            String content = response1.getContentAsString();
            assertEquals(body1, content);

            Assert.assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));

            String body2 = "body=" + body1;
            org.eclipse.jetty.client.api.Request request2 = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .method(HttpMethod.POST)
                    .path("/echo")
                    .header(HttpHeader.CONTENT_TYPE, MimeTypes.Type.FORM_ENCODED.asString())
                    .header(HttpHeader.CONTENT_LENGTH, String.valueOf(body2.length()))
                    .content(new StringContentProvider(body2));

            // Make sure the second connection can send the exchange via the tunnel
            FutureResponseListener listener2 = new FutureResponseListener(request2);
            connection.get().send(request2, listener2);
            ContentResponse response2 = listener2.get(5, TimeUnit.SECONDS);

            assertEquals(HttpStatus.OK_200, response2.getStatus());
            String content2 = response1.getContentAsString();
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

        HttpClient httpClient = new HttpClient(sslContextFactory);
        httpClient.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyPort));
        httpClient.start();

        try
        {
            String body = "BODY";
            httpClient.newRequest("localhost", serverConnector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .method(HttpMethod.GET)
                    .path("/echo?body=" + URLEncoder.encode(body, "UTF-8"))
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertThat(x.getCause(), Matchers.instanceOf(ConnectException.class));
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

        HttpClient httpClient = new HttpClient(sslContextFactory);
        httpClient.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyPort()));
        httpClient.start();

        try
        {
            String body = "BODY";
            httpClient.newRequest("localhost", serverPort)
                    .scheme(HttpScheme.HTTPS.asString())
                    .method(HttpMethod.GET)
                    .path("/echo?body=" + URLEncoder.encode(body, "UTF-8"))
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            // Expected
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testProxyClosesConnection() throws Exception
    {
        startSSLServer(new ServerHandler());
        startProxy(new ConnectHandler()
        {
            @Override
            protected void handleConnect(Request jettyRequest, HttpServletRequest request, HttpServletResponse response, String serverAddress)
            {
                HttpConnection.getCurrentConnection().close();
            }
        });

        HttpClient httpClient = new HttpClient(sslContextFactory);
        httpClient.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyPort()));
        httpClient.start();

        try
        {
            httpClient.newRequest("localhost", serverConnector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            // Expected
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    @Ignore("External Proxy Server no longer stable enough for testing")
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

        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.start();

        HttpClient httpClient = new HttpClient(sslContextFactory);
        httpClient.getProxyConfiguration().getProxies().add(new HttpProxy(proxyHost, proxyPort));
        httpClient.start();

        try
        {
            ContentResponse response = httpClient.newRequest("https://www.google.com")
                    // Use a longer timeout, sometimes the proxy takes a while to answer
                    .timeout(20, TimeUnit.SECONDS)
                    .send();
            assertEquals(HttpStatus.OK_200, response.getStatus());
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
