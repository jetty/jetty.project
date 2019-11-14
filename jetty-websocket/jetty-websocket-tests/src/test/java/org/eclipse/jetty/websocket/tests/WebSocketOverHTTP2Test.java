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

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketOverHTTP2Test
{
    private Server server;
    private ServerConnector connector;
    private ServerConnector tlsConnector;

    @BeforeEach
    public void startServer() throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        HttpConfiguration httpConfig = new HttpConfiguration();
        HttpConnectionFactory h1c = new HttpConnectionFactory(httpConfig);
        HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfig);
        connector = new ServerConnector(server, 1, 1, h1c, h2c);
        server.addConnector(connector);

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);

        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory h1s = new HttpConnectionFactory(httpsConfig);
        HTTP2ServerConnectionFactory h2s = new HTTP2ServerConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1c.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
        tlsConnector = new ServerConnector(server, 1, 1, ssl, alpn, h2s, h1s);
        server.addConnector(tlsConnector);

        ServletContextHandler context = new ServletContextHandler(server, "/");
        context.addServlet(new ServletHolder(new JettyWebSocketServlet()
        {
            @Override
            protected void configure(JettyWebSocketServletFactory factory)
            {
                factory.addMapping("/ws/echo", (req, resp) -> new EchoSocket());
            }
        }), "/ws/*");
        JettyWebSocketServletContainerInitializer.initialize(context);

        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testWebSocketOverDynamicHTTP1() throws Exception
    {
        testWebSocketOverDynamicTransport(clientConnector -> HttpClientConnectionFactory.HTTP11);
    }

    @Test
    public void testWebSocketOverDynamicHTTP2() throws Exception
    {
        testWebSocketOverDynamicTransport(clientConnector -> new ClientConnectionFactoryOverHTTP2.H2C(new HTTP2Client(clientConnector)));
    }

    private void testWebSocketOverDynamicTransport(Function<ClientConnector, ClientConnectionFactory.Info> protocolFn) throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector, protocolFn.apply(clientConnector)));

        WebSocketClient wsClient = new WebSocketClient(httpClient);
        wsClient.start();

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/ws/echo");
        Session session = wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS);

        String text = "websocket";
        session.getRemote().sendString(text);

        String message = wsEndPoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals(text, message);

        session.close(StatusCode.NORMAL, null);
        assertTrue(wsEndPoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertEquals(StatusCode.NORMAL, wsEndPoint.statusCode);
        assertNull(wsEndPoint.error);
    }

    @Test
    public void testConnectProtocolDisabled() throws Exception
    {
        AbstractHTTP2ServerConnectionFactory h2c = connector.getBean(AbstractHTTP2ServerConnectionFactory.class);
        h2c.setConnectProtocolEnabled(false);

        ClientConnector clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector, new ClientConnectionFactoryOverHTTP2.H2C(http2Client)));

        WebSocketClient wsClient = new WebSocketClient(httpClient);
        wsClient.start();

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/ws/echo");

        ExecutionException failure = Assertions.assertThrows(ExecutionException.class, () ->
            wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));

        Throwable cause = failure.getCause();
        assertThat(cause.getMessage(), containsStringIgnoringCase(ErrorCode.PROTOCOL_ERROR.name()));
    }
}
