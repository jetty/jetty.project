//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.tests;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee10.websocket.server.internal.DelegatedServerUpgradeRequest;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnectionLimit;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.EventsHandler;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.exceptions.UpgradeException;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.awaitility.Awaitility.await;
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
    private Runnable onComplete;

    private void prepareAndStartServer() throws Exception
    {
        prepareServer();
        server.start();
    }

    private void prepareServer() throws Exception
    {
        prepareServer(new TestJettyWebSocketServlet());
    }

    private void prepareServer(TestJettyWebSocketServlet servlet) throws Exception
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
        sslContextFactory.setKeyStorePath(MavenTestingUtils.getTestResourcePath("keystore.p12").toString());
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

        context = new ServletContextHandler("/");
        server.setHandler(context);
        context.addServlet(new ServletHolder(servlet), "/ws/*");
        JettyWebSocketServletContainerInitializer.configure(context, null);

        server.setHandler(new EventsHandler(server.getHandler())
        {
            @Override
            protected void onComplete(Request request, int status, HttpFields headers, Throwable failure)
            {
                if (onComplete != null)
                    onComplete.run();
            }
        });
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
        onComplete = null;
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
    @Tag("flaky") // See analysis in #12235.
    public void testWebSocketOverDynamicHTTP2() throws Exception
    {
        testWebSocketOverDynamicTransport(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));
    }

    private void testWebSocketOverDynamicTransport(Function<ClientConnector, ClientConnectionFactory.Info> protocolFn) throws Exception
    {
        prepareAndStartServer();
        startClient(protocolFn);

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/ws/echo/query?param=value");
        Session session = wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS);

        String text = "websocket";
        session.sendText(text, Callback.NOOP);

        String message = wsEndPoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals(text, message);

        session.close(StatusCode.NORMAL, null, Callback.NOOP);
        assertTrue(wsEndPoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertEquals(StatusCode.NORMAL, wsEndPoint.closeCode);
        assertNull(wsEndPoint.error);
    }

    @Test
    public void testConnectProtocolDisabled() throws Exception
    {
        prepareAndStartServer();
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
        prepareServer(new TestJettyWebSocketServlet()
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
        server.start();

        startClient(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));

        // Connect and send immediately a message, so the message
        // arrives to the server while the server is still upgrading.
        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("wss://localhost:" + tlsConnector.getLocalPort() + "/ws/echo");
        Session session = wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS);
        String text = "websocket";
        session.sendText(text, Callback.NOOP);

        String message = wsEndPoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals(text, message);

        session.close(StatusCode.NORMAL, null, Callback.NOOP);
        assertTrue(wsEndPoint.closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Issue #6660 - Windows does not throw ConnectException")
    public void testWebSocketConnectPortDoesNotExist() throws Exception
    {
        prepareAndStartServer();
        startClient(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));

        // Port 293 is not assigned by IANA, so
        // it should be impossible to connect.
        int nonExistingPort = 293;
        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + nonExistingPort + "/ws/echo");

        ExecutionException failure = Assertions.assertThrows(ExecutionException.class, () ->
            wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));

        Throwable cause = failure.getCause();
        assertThat(cause, instanceOf(ConnectException.class));
        assertThat(cause.getMessage(), containsStringIgnoringCase("Connection refused"));
    }

    @Test
    public void testWebSocketNotFound() throws Exception
    {
        prepareAndStartServer();
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
        prepareAndStartServer();
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
        prepareAndStartServer();
        startClient(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));

        CountDownLatch latch = new CountDownLatch(1);
        onComplete = latch::countDown;
        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/ws/throw");

        ExecutionException failure;
        try (StacklessLogging ignored = new StacklessLogging(HttpChannelState.class))
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
        prepareAndStartServer();
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
        prepareAndStartServer();
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
        session.sendText("hello world", Callback.NOOP);
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

    @Test
    @Disabled("This test fails due to an issue with the WebSocket over HTTP/2 implementation, see https://github.com/jetty/jetty.project/issues/13349")
    public void testNetworkConnectionLimit() throws Exception
    {
        prepareServer();

        int maxNetworkConnectionCount = 5;
        NetworkConnectionLimit networkConnectionLimit = new NetworkConnectionLimit(maxNetworkConnectionCount, connector, tlsConnector);
        connector.addBean(networkConnectionLimit);
        tlsConnector.addBean(networkConnectionLimit);

        server.start();

        JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(context.getServletContext());
        startClient(clientConnector -> new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)));
        EchoSocket serverEndpoint = new EchoSocket();
        container.addMapping("/specialEcho", (req, resp) -> serverEndpoint);
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/specialEcho");

        List<EventSocket> clientHandlers = new ArrayList<>();
        for (int i = 0; i < maxNetworkConnectionCount; i++)
        {
            EventSocket clientEndpoint = new EventSocket();
            clientHandlers.add(clientEndpoint);
            wsClient.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);
            assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));
            assertThat(clientEndpoint.session.getUpgradeRequest().getHttpVersion(), equalTo(HttpVersion.HTTP_2.asString()));
            awaitConnections(1, networkConnectionLimit);
        }

        // We only have 1 HTTP2Connection, and the WebSocket connections are over HTTP/2 streams so do not count toward the limit.
        assertThat(networkConnectionLimit.getPendingNetworkConnectionCount(), equalTo(0));
        assertThat(networkConnectionLimit.getNetworkConnectionCount(), equalTo(1));

        // Close all the sessions.
        for (EventSocket handler : clientHandlers)
        {
            handler.session.close();
            assertTrue(handler.closeLatch.await(5, TimeUnit.SECONDS));
            assertThat(handler.closeCode, equalTo(CloseStatus.NORMAL));
        }

        assertThat(networkConnectionLimit.getPendingNetworkConnectionCount(), equalTo(0));
        assertThat(networkConnectionLimit.getNetworkConnectionCount(), equalTo(1));
    }

    private static void awaitConnections(int connections, NetworkConnectionLimit networkConnectionLimit)
    {
        await().atMost(1, TimeUnit.SECONDS)
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() ->
            {
                assertThat(networkConnectionLimit.getNetworkConnectionCount(), equalTo(connections));
                assertThat(networkConnectionLimit.getPendingNetworkConnectionCount(), equalTo(0));
            });
    }

    private static class TestJettyWebSocketServlet extends JettyWebSocketServlet
    {
        @Override
        protected void configure(JettyWebSocketServletFactory factory)
        {
            factory.addMapping("/ws/echo", (request, response) -> new EchoSocket());
            factory.addMapping("/ws/echo/query", (request, response) ->
            {
                assertNotNull(request.getQueryString());
                return new EchoSocket();
            });
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
                Request coreRequest = ((DelegatedServerUpgradeRequest)request).getServerUpgradeRequest();
                coreRequest.getConnectionMetaData().getConnection().getEndPoint().close();
                return new EchoSocket();
            });
        }
    }
}
