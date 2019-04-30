//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http.client;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ProxyTestWithDynamicTransport
{
    private static final Logger LOG = Log.getLogger(ProxyTestWithDynamicTransport.class);

    private Server server;
    private ServerConnector serverConnector;
    private ServerConnector serverTLSConnector;
    private Server proxy;
    private ServerConnector proxyConnector;
    private ServerConnector proxyTLSConnector;
    private HttpClient client;

    private void start(Handler handler) throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setUseCipherSuitesOrder(true);
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);

        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        HttpConfiguration httpConfig = new HttpConfiguration();
        HttpConnectionFactory h1c = new HttpConnectionFactory(httpConfig);
        HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfig);
        serverConnector = new ServerConnector(server, 1, 1, h1c, h2c);
        server.addConnector(serverConnector);
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpsConfig);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
        serverTLSConnector = new ServerConnector(server, 1, 1, ssl, alpn, h2, h1, h2c);
        server.addConnector(serverTLSConnector);
        server.setHandler(handler);
        server.start();
        LOG.info("Started server on :{} and :{}", serverConnector.getLocalPort(), serverTLSConnector.getLocalPort());

        startProxy();

        startClient();
    }

    private void startProxy() throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setUseCipherSuitesOrder(true);
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);

        QueuedThreadPool proxyThreads = new QueuedThreadPool();
        proxyThreads.setName("proxy");
        proxy = new Server(proxyThreads);

        HttpConfiguration httpConfig = new HttpConfiguration();
        ConnectionFactory h1c = new HttpConnectionFactory(httpConfig);
        ConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfig);
        proxyConnector = new ServerConnector(proxy, 1, 1, h1c, h2c);
        proxy.addConnector(proxyConnector);
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpsConfig);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
        proxyTLSConnector = new ServerConnector(proxy, 1, 1, ssl, alpn, h2, h1, h2c);
        proxy.addConnector(proxyTLSConnector);

        ConnectHandler connectHandler = new ConnectHandler();
        proxy.setHandler(connectHandler);
        ServletContextHandler context = new ServletContextHandler(connectHandler, "/");
        ServletHolder holder = new ServletHolder(new AsyncProxyServlet()
        {
            @Override
            protected HttpClient newHttpClient(int selectors)
            {
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSelectors(1);
                ClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP11;
                HTTP2Client http2Client = new HTTP2Client(clientConnector);
                ClientConnectionFactory.Info h2c = new ClientConnectionFactoryOverHTTP2.H2C(http2Client);
                ClientConnectionFactory.Info h2 = new ClientConnectionFactoryOverHTTP2.H2(http2Client);
                return new HttpClient(new HttpClientTransportDynamic(clientConnector, h1, h2c, h2));
            }
        });
        context.addServlet(holder, "/*");
        proxy.start();
        LOG.info("Started proxy on :{} and :{}", proxyConnector.getLocalPort(), proxyTLSConnector.getLocalPort());
    }

    private void startClient() throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        clientConnector.setExecutor(clientThreads);
        clientConnector.setSslContextFactory(new SslContextFactory.Client(true));
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP11;
        ClientConnectionFactory.Info h2c = new ClientConnectionFactoryOverHTTP2.H2C(http2Client);
        ClientConnectionFactory.Info h2 = new ClientConnectionFactoryOverHTTP2.H2(http2Client);
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, h1, h2c, h2));
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
        if (proxy != null)
            proxy.stop();
        if (client != null)
            client.stop();
    }

    private static Stream<Arguments> testParams()
    {
        var h1 = List.of("http/1.1");
        var h2c = List.of("h2c");
        var h2 = List.of("h2");
        return Stream.of(
                // HTTP/1.1 Proxy with HTTP/1.1 Server.
                Arguments.of(new HttpDestination.Protocol(h1, false), false, HttpVersion.HTTP_1_1, false),
                Arguments.of(new HttpDestination.Protocol(h1, false), false, HttpVersion.HTTP_1_1, true),
                Arguments.of(new HttpDestination.Protocol(h1, false), true, HttpVersion.HTTP_1_1, false),
                Arguments.of(new HttpDestination.Protocol(h1, false), true, HttpVersion.HTTP_1_1, true),
                // HTTP/1.1 Proxy with HTTP/2 Server.
                Arguments.of(new HttpDestination.Protocol(h1, false), false, HttpVersion.HTTP_2, false),
                Arguments.of(new HttpDestination.Protocol(h1, false), false, HttpVersion.HTTP_2, true),
                Arguments.of(new HttpDestination.Protocol(h1, false), true, HttpVersion.HTTP_2, false),
                Arguments.of(new HttpDestination.Protocol(h1, false), true, HttpVersion.HTTP_2, true),
                // HTTP/2 Proxy with HTTP/1.1 Server.
                Arguments.of(new HttpDestination.Protocol(h2c, false), false, HttpVersion.HTTP_1_1, false),
                Arguments.of(new HttpDestination.Protocol(h2c, false), false, HttpVersion.HTTP_1_1, true),
                Arguments.of(new HttpDestination.Protocol(h2, false), true, HttpVersion.HTTP_1_1, false),
                Arguments.of(new HttpDestination.Protocol(h2, false), true, HttpVersion.HTTP_1_1, true),
                Arguments.of(new HttpDestination.Protocol(h2, true), true, HttpVersion.HTTP_1_1, false),
                Arguments.of(new HttpDestination.Protocol(h2, true), true, HttpVersion.HTTP_1_1, true),
                // HTTP/2 Proxy with HTTP/2 Server.
                Arguments.of(new HttpDestination.Protocol(h2c, false), false, HttpVersion.HTTP_2, false),
                Arguments.of(new HttpDestination.Protocol(h2c, false), false, HttpVersion.HTTP_2, true),
                Arguments.of(new HttpDestination.Protocol(h2, false), true, HttpVersion.HTTP_2, false),
                Arguments.of(new HttpDestination.Protocol(h2, false), true, HttpVersion.HTTP_2, true),
                Arguments.of(new HttpDestination.Protocol(h2, true), true, HttpVersion.HTTP_2, false),
                Arguments.of(new HttpDestination.Protocol(h2, true), true, HttpVersion.HTTP_2, true)
        );
    }

    @ParameterizedTest(name = "proxyProtocol={0}, proxySecure={1}, serverProtocol={2}, serverSecure={3}")
    @MethodSource("testParams")
    public void testProxy(HttpDestination.Protocol proxyProtocol, boolean proxySecure, HttpVersion serverProtocol, boolean serverSecure) throws Exception
    {
        int status = HttpStatus.NO_CONTENT_204;
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                response.setStatus(status);
            }
        });

        int proxyPort = proxySecure ? proxyTLSConnector.getLocalPort() : proxyConnector.getLocalPort();
        Origin.Address proxyAddress = new Origin.Address("localhost", proxyPort);
        HttpProxy proxy = new HttpProxy(proxyAddress, proxySecure, proxyProtocol);
        client.getProxyConfiguration().getProxies().add(proxy);

        int serverPort = serverSecure ? serverTLSConnector.getLocalPort() : serverConnector.getLocalPort();
        ContentResponse response = client.newRequest("localhost", serverPort)
                .scheme(serverSecure ? "https" : "http")
                .version(serverProtocol)
                .timeout(555, TimeUnit.SECONDS)
                .send();

        assertEquals(status, response.getStatus());

        // TODO: should test that a second request goes through the same connection.
    }
}
