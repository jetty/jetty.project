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

package org.eclipse.jetty.ee9.websocket.tests;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.ee9.nested.HttpChannel;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.StatusCode;
import org.eclipse.jetty.ee9.websocket.api.exceptions.UpgradeException;
import org.eclipse.jetty.ee9.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.internal.ErrorCode;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketOverHTTP2Test
{
    private Server server;
    private ServerConnector connector;
    private ServerConnector tlsConnector;
    private WebSocketClient wsClient;
    private ServletContextHandler context;

    private void startServer() throws Exception
    {
        startServer(new TestJettyWebSocketServlet());
    }

    private void startServer(TestJettyWebSocketServlet servlet) throws Exception
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
        sslContextFactory.setKeyStorePath(MavenTestingUtils.getTargetPath("test-classes/keystore.p12").toString());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);

        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory h1s = new HttpConnectionFactory(httpsConfig);
        HTTP2ServerConnectionFactory h2s = new HTTP2ServerConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1s.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
        tlsConnector = new ServerConnector(server, 1, 1, ssl, alpn, h1s, h2s);
        server.addConnector(tlsConnector);

        context = new ServletContextHandler(server, "/");
        context.addServlet(new ServletHolder(servlet), "/ws/*");
        JettyWebSocketServletContainerInitializer.configure(context, null);

        server.start();
    }

    private void startClient(Function<ClientConnector, ClientConnectionFactory.Info> protocolFn) throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(new SslContextFactory.Client(true));
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector, protocolFn.apply(clientConnector)));
        wsClient = new WebSocketClient(httpClient);
        wsClient.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
        if (wsClient != null)
            wsClient.stop();
    }

    @Test
    public void testWebSocketOverDynamicHTTP1() throws Exception
    {
        testWebSocketOverDynamicTransport(clientConnector -> HttpClientConnectionFactory.HTTP11);
    }

    @Test
    public void testWebSocketOverDynamicHTTP2() throws Exception
    {
        testWebSocketOverDynamicTransport(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));
    }

    private void testWebSocketOverDynamicTransport(Function<ClientConnector, ClientConnectionFactory.Info> protocolFn) throws Exception
    {
        startServer();
        startClient(protocolFn);

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/ws/echo");
        Session session = wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS);

        String text = "websocket";
        session.getRemote().sendString(text);

        String message = wsEndPoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals(text, message);

        session.close(StatusCode.NORMAL, null);
        assertTrue(wsEndPoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertEquals(StatusCode.NORMAL, wsEndPoint.closeCode);
        assertNull(wsEndPoint.error);
    }

    @Test
    public void testConnectProtocolDisabled() throws Exception
    {
        startServer();
        AbstractHTTP2ServerConnectionFactory h2c = connector.getBean(AbstractHTTP2ServerConnectionFactory.class);
        h2c.setConnectProtocolEnabled(false);

        startClient(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/ws/echo");

        ExecutionException failure = Assertions.assertThrows(ExecutionException.class, () ->
            wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));

        Throwable cause = failure.getCause();
        assertThat(cause.getMessage(), containsStringIgnoringCase(ErrorCode.PROTOCOL_ERROR.name()));
    }

    @Test
    public void testSlowWebSocketUpgradeWithHTTP2DataFramesQueued() throws Exception
    {
        startServer(new TestJettyWebSocketServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                try
                {
                    super.service(request, response);
                    // Flush the response to the client then wait before exiting
                    // this method so that the client can send HTTP/2 DATA frames
                    // that will be processed by the server while this method sleeps.
                    response.flushBuffer();
                    Thread.sleep(1000);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });

        startClient(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));

        // Connect and send immediately a message, so the message
        // arrives to the server while the server is still upgrading.
        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("wss://localhost:" + tlsConnector.getLocalPort() + "/ws/echo");
        Session session = wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS);
        String text = "websocket";
        session.getRemote().sendString(text);

        String message = wsEndPoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals(text, message);

        session.close(StatusCode.NORMAL, null);
        assertTrue(wsEndPoint.closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Issue #6660 - Windows does not throw ConnectException")
    public void testWebSocketConnectPortDoesNotExist() throws Exception
    {
        startServer();
        startClient(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + (connector.getLocalPort() + 1) + "/ws/echo");

        ExecutionException failure = Assertions.assertThrows(ExecutionException.class, () ->
            wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));

        Throwable cause = failure.getCause();
        assertThat(cause, instanceOf(ConnectException.class));
        assertThat(cause.getMessage(), containsStringIgnoringCase("Connection refused"));
    }

    @Test
    public void testWebSocketNotFound() throws Exception
    {
        startServer();
        startClient(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/nothing");

        ExecutionException failure = Assertions.assertThrows(ExecutionException.class, () ->
            wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));

        Throwable cause = failure.getCause();
        assertThat(cause, instanceOf(UpgradeException.class));
        assertThat(cause.getMessage(), containsStringIgnoringCase("Unexpected HTTP Response Status Code: 501"));
    }

    @Test
    public void testNotNegotiated() throws Exception
    {
        startServer();
        startClient(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/ws/null");

        ExecutionException failure = Assertions.assertThrows(ExecutionException.class, () ->
            wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));

        Throwable cause = failure.getCause();
        assertThat(cause, instanceOf(UpgradeException.class));
        assertThat(cause.getMessage(), containsStringIgnoringCase("Unexpected HTTP Response Status Code: 503"));
    }

    @Test
    public void testThrowFromCreator() throws Exception
    {
        startServer();
        startClient(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));

        CountDownLatch latch = new CountDownLatch(1);
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onComplete(org.eclipse.jetty.ee9.nested.Request request)
            {
                latch.countDown();
            }
        });

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/ws/throw");

        ExecutionException failure;
        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class))
        {
            failure = Assertions.assertThrows(ExecutionException.class, () ->
                wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));
        }

        Throwable cause = failure.getCause();
        assertThat(cause, instanceOf(UpgradeException.class));
        assertThat(cause.getMessage(), containsStringIgnoringCase("Unexpected HTTP Response Status Code: 500"));

        // Wait for the request to complete on server before stopping.
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerConnectionClose() throws Exception
    {
        startServer();
        startClient(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/ws/connectionClose");

        ExecutionException failure = Assertions.assertThrows(ExecutionException.class, () ->
            wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));

        Throwable cause = failure.getCause();
        assertThat(cause, instanceOf(ClosedChannelException.class));
    }

    @Test
    public void testServerTimeout() throws Exception
    {
        startServer();
        JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(context.getServletContext());
        startClient(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));
        EchoSocket serverEndpoint = new EchoSocket();
        container.addMapping("/specialEcho", (req, resp) -> serverEndpoint);

        // Set up idle timeouts.
        long timeout = 1000;
        container.setIdleTimeout(Duration.ofMillis(timeout));
        wsClient.setIdleTimeout(Duration.ZERO);

        // Setup a websocket connection.
        EventSocket clientEndpoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/specialEcho");
        Session session = wsClient.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);
        session.getRemote().sendString("hello world");
        String received = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(received, equalTo("hello world"));

        // Wait for timeout on server.
        assertTrue(serverEndpoint.closeLatch.await(timeout * 2, TimeUnit.MILLISECONDS));
        assertThat(serverEndpoint.closeCode, equalTo(StatusCode.SHUTDOWN));
        assertThat(serverEndpoint.closeReason, containsStringIgnoringCase("timeout"));
        assertNotNull(serverEndpoint.error);

        // Wait for timeout on client.
        assertTrue(clientEndpoint.closeLatch.await(timeout * 2, TimeUnit.MILLISECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.SHUTDOWN));
        assertThat(clientEndpoint.closeReason, containsStringIgnoringCase("timeout"));
        assertNull(clientEndpoint.error);
    }

    private static class TestJettyWebSocketServlet extends JettyWebSocketServlet
    {
        @Override
        protected void configure(JettyWebSocketServletFactory factory)
        {
            factory.addMapping("/ws/echo", (request, response) -> new EchoSocket());
            factory.addMapping("/ws/null", (request, response) ->
            {
                response.sendError(HttpStatus.SERVICE_UNAVAILABLE_503, "null");
                return null;
            });
            factory.addMapping("/ws/throw", (request, response) ->
            {
                throw new RuntimeException("throwing from creator");
            });
            factory.addMapping("/ws/connectionClose", (request, response) ->
            {
                HttpServletRequest upgradeRequest = request.getHttpServletRequest();
                ((org.eclipse.jetty.ee9.nested.Request)upgradeRequest).getHttpChannel().getEndPoint().close();
                return new EchoSocket();
            });
        }
    }
}
