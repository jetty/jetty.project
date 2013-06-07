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

package org.eclipse.jetty.proxy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URLEncoder;
import java.util.concurrent.ExecutionException;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.ProxyConfiguration;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
        server.stop();
        server.join();
    }

    protected void stopProxy() throws Exception
    {
        proxy.stop();
        proxy.join();
    }

    @Test
    public void testOneMessageSSL() throws Exception
    {
        startSSLServer(new ServerHandler());
        startProxy();

        HttpClient httpClient = new HttpClient(sslContextFactory);
        httpClient.setProxyConfiguration(new ProxyConfiguration("localhost", proxyPort()));
        httpClient.start();

        try
        {
            String body = "BODY";
            ContentResponse response = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .method(HttpMethod.GET)
                    .path("/echo?body=" + URLEncoder.encode(body, "UTF-8"))
                    .send();

            String content = response.getContentAsString();
            assertEquals(body, content);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testTwoMessagesSSL() throws Exception
    {
        startSSLServer(new ServerHandler());
        startProxy();

        HttpClient httpClient = new HttpClient(sslContextFactory);
        httpClient.setProxyConfiguration(new ProxyConfiguration("localhost", proxyPort()));
        httpClient.start();

        try
        {
            String body = "BODY";
            ContentResponse response1 = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .method(HttpMethod.GET)
                    .path("/echo?body=" + URLEncoder.encode(body, "UTF-8"))
                    .send();

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

            content = response2.getContentAsString();
            assertEquals(body, content);
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
        httpClient.setProxyConfiguration(new ProxyConfiguration("localhost", proxyPort));
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
        httpClient.setProxyConfiguration(new ProxyConfiguration("localhost", proxyPort()));
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
        httpClient.setProxyConfiguration(new ProxyConfiguration("localhost", proxyPort()));
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
