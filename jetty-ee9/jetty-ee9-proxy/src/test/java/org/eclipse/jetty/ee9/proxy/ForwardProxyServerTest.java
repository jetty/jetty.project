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

package org.eclipse.jetty.ee9.proxy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.ConnectHandler;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class ForwardProxyServerTest
{
    public static Stream<SslContextFactory.Server> serverTLS()
    {
        return Stream.of(null, newServerSslContextFactory());
    }

    private static SslContextFactory.Server newServerSslContextFactory()
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();
        String keyStorePath = MavenTestingUtils.getTestResourceFile("server_keystore.p12").getAbsolutePath();
        serverTLS.setKeyStorePath(keyStorePath);
        serverTLS.setKeyStorePassword("storepwd");
        return serverTLS;
    }

    private Server server;
    private ServerConnector serverConnector;
    private SslContextFactory.Server serverSslContextFactory;
    private Server proxy;
    private ServerConnector proxyConnector;

    protected void startServer(SslContextFactory.Server serverTLS, ConnectionFactory connectionFactory, Handler handler) throws Exception
    {
        serverSslContextFactory = serverTLS;
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        serverConnector = new ServerConnector(server, serverSslContextFactory, connectionFactory);
        server.addConnector(serverConnector);
        server.setHandler(handler);
        server.start();
    }

    protected void startProxy(ProxyServlet proxyServlet) throws Exception
    {
        QueuedThreadPool proxyThreads = new QueuedThreadPool();
        proxyThreads.setName("proxy");
        proxy = new Server(proxyThreads);
        proxyConnector = new ServerConnector(proxy);
        proxy.addConnector(proxyConnector);
        // Under Windows, it takes a while to detect that a connection
        // attempt fails, so use an explicit timeout
        ConnectHandler connectHandler = new ConnectHandler();
        connectHandler.setConnectTimeout(1000);
        proxy.setHandler(connectHandler);

        ServletContextHandler proxyHandler = new ServletContextHandler(connectHandler, "/");
        proxyHandler.addServlet(new ServletHolder(proxyServlet), "/*");

        proxy.start();
    }

    protected HttpProxy newHttpProxy()
    {
        return new HttpProxy("localhost", proxyConnector.getLocalPort());
    }

    @AfterEach
    public void stop() throws Exception
    {
        stopProxy();
        stopServer();
    }

    protected void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }

    protected void stopProxy() throws Exception
    {
        if (proxy != null)
            proxy.stop();
    }

    @ParameterizedTest
    @MethodSource("serverTLS")
    public void testRequestTarget(SslContextFactory.Server serverTLS) throws Exception
    {
        startServer(serverTLS, new AbstractConnectionFactory("http/1.1")
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                return new AbstractConnection(endPoint, connector.getExecutor())
                {
                    @Override
                    public void onOpen()
                    {
                        super.onOpen();
                        fillInterested();
                    }

                    @Override
                    public void onFillable()
                    {
                        try
                        {
                            // When using TLS, multiple reads are required.
                            ByteBuffer buffer = BufferUtil.allocate(1024);
                            int filled = 0;
                            while (filled == 0)
                            {
                                filled = getEndPoint().fill(buffer);
                            }
                            Utf8StringBuilder builder = new Utf8StringBuilder();
                            builder.append(buffer);
                            String request = builder.toString();

                            // ProxyServlet will receive an absolute URI from
                            // the client, and convert it to a relative URI.
                            // The ConnectHandler won't modify what the client
                            // sent, which must be a relative URI.
                            assertThat(request.length(), greaterThan(0));
                            if (serverSslContextFactory == null)
                                assertFalse(request.contains("http://"));
                            else
                                assertFalse(request.contains("https://"));

                            String response = """
                                HTTP/1.1 200 OK
                                Content-Length: 0
                                
                                """;
                            getEndPoint().write(Callback.NOOP, ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
                        }
                        catch (Throwable x)
                        {
                            x.printStackTrace();
                            close();
                        }
                    }
                };
            }
        }, new EmptyServerHandler());
        startProxy(new ProxyServlet());

        SslContextFactory.Client clientTLS = new SslContextFactory.Client(true);
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(clientTLS);
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        httpClient.getProxyConfiguration().getProxies().add(newHttpProxy());
        httpClient.start();

        try
        {
            ContentResponse response = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                .scheme(serverSslContextFactory == null ? "http" : "https")
                .method(HttpMethod.GET)
                .path("/test")
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
        finally
        {
            httpClient.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"::2", "[::3]"})
    public void testIPv6WithXForwardedForHeader(String ipv6) throws Exception
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new ForwardedRequestCustomizer());
        ConnectionFactory http = new HttpConnectionFactory(httpConfig);
        startServer(null, http, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String remoteHost = org.eclipse.jetty.server.Request.getRemoteAddr(request);
                assertThat(remoteHost, Matchers.matchesPattern("\\[.+\\]"));
                callback.succeeded();
            }
        });
        startProxy(new ProxyServlet()
        {
            @Override
            protected void addProxyHeaders(HttpServletRequest clientRequest, Request proxyRequest)
            {
                proxyRequest.headers(headers -> headers.put(HttpHeader.X_FORWARDED_FOR, ipv6));
            }
        });

        HttpClient httpClient = new HttpClient();
        httpClient.getProxyConfiguration().getProxies().add(newHttpProxy());
        httpClient.start();

        ContentResponse response = httpClient.newRequest("[::1]", serverConnector.getLocalPort())
            .scheme("http")
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testIPv6WithForwardedHeader() throws Exception
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new ForwardedRequestCustomizer());
        ConnectionFactory http = new HttpConnectionFactory(httpConfig);
        startServer(null, http, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String remoteHost = org.eclipse.jetty.server.Request.getRemoteAddr(request);
                assertThat(remoteHost, Matchers.matchesPattern("\\[.+\\]"));
                callback.succeeded();
            }
        });
        startProxy(new ProxyServlet()
        {
            @Override
            protected void addProxyHeaders(HttpServletRequest clientRequest, Request proxyRequest)
            {
                proxyRequest.headers(headers -> headers.put(HttpHeader.FORWARDED, "for=\"[::2]\""));
            }
        });

        HttpClient httpClient = new HttpClient();
        httpClient.getProxyConfiguration().getProxies().add(newHttpProxy());
        httpClient.start();

        ContentResponse response = httpClient.newRequest("[::1]", serverConnector.getLocalPort())
            .scheme("http")
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }
}
