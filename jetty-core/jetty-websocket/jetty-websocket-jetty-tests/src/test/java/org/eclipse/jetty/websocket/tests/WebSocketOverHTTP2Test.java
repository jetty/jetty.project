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

package org.eclipse.jetty.websocket.tests;

import java.net.ConnectException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpRequestException;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.SessionContainer;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnectionLimit;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.EventsHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.exceptions.UpgradeException;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.JettyUpgradeListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketOverHTTP2Test
{
    private Server server;
    private ServerConnector connector;
    private ServerConnector tlsConnector;
    private WebSocketClient wsClient;
    private ContextHandler context;
    private Runnable onComplete;

    private void startServer(Consumer<ServerWebSocketContainer> configurator) throws Exception
    {
        prepareServer(configurator);
        server.start();
    }

    private void startServer(BiFunction<Server, ContextHandler, Handler> wsHandlerFactory) throws Exception
    {
        prepareServer(wsHandlerFactory);
        server.start();
    }

    private void prepareServer(Consumer<ServerWebSocketContainer> configurator)
    {
        prepareServer((server, context) -> WebSocketUpgradeHandler.from(server, context, configurator));
    }

    private void prepareServer(BiFunction<Server, ContextHandler, Handler> wsHandlerFactory)
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

        EventsHandler eventsHandler = new EventsHandler()
        {
            @Override
            protected void onComplete(Request request, int status, HttpFields headers, Throwable failure)
            {
                if (onComplete != null)
                    onComplete.run();
            }
        };
        server.setHandler(eventsHandler);

        context = new ContextHandler("/");
        eventsHandler.setHandler(context);

        Handler wsHandler = wsHandlerFactory.apply(server, context);
        context.setHandler(wsHandler);
    }

    private void startClient(Function<ClientConnector, List<ClientConnectionFactory.Info>> protocolFn) throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(new SslContextFactory.Client(true));
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector, protocolFn.apply(clientConnector).toArray(ClientConnectionFactory.Info[]::new)));
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
        testWebSocketOverDynamicTransport(clientConnector -> List.of(HttpClientConnectionFactory.HTTP11));
    }

    @Test
    @Tag("flaky") // See analysis in #12235.
    public void testWebSocketOverDynamicHTTP2() throws Exception
    {
        testWebSocketOverDynamicTransport(clientConnector -> List.of(new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector))));
    }

    private void testWebSocketOverDynamicTransport(Function<ClientConnector, List<ClientConnectionFactory.Info>> protocolFn) throws Exception
    {
        startServer(container -> container.addMapping("/echo/*", (rq, rs, cb) -> new EchoSocket()));
        startClient(protocolFn);

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/echo/query?param=value");
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
        startServer(container -> container.addMapping("/echo", (rq, rs, cb) -> new EchoSocket()));
        AbstractHTTP2ServerConnectionFactory h2c = connector.getBean(AbstractHTTP2ServerConnectionFactory.class);
        h2c.setConnectProtocolEnabled(false);

        startClient(clientConnector -> List.of(new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector))));

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/echo");

        assertThrows(ExecutionException.class, () -> wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSlowWebSocketUpgradeWithHTTP2DataFramesQueued() throws Exception
    {
        startServer((server, context) -> new Handler.Wrapper(WebSocketUpgradeHandler.from(server, context, container ->
            container.addMapping("/echo", (rq, rs, cb) -> new EchoSocket())))
        {
            @Override
            public boolean handle(Request request, Response response, org.eclipse.jetty.util.Callback callback) throws Exception
            {
                boolean handled = super.handle(request, response, callback);
                // The response has been sent to the client; wait before exiting
                // this method so that the client can send HTTP/2 DATA frames
                // that will be processed by the server while this method sleeps.
                Thread.sleep(1000);
                return handled;
            }
        });
        server.start();

        startClient(clientConnector -> List.of(new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector))));

        // Connect and send immediately a message, so the message
        // arrives to the server while the server is still upgrading.
        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("wss://localhost:" + tlsConnector.getLocalPort() + "/echo");
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
        startServer(container -> container.addMapping("/echo", (rq, rs, cb) -> new EchoSocket()));
        startClient(clientConnector -> List.of(new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector))));

        // Port 293 is not assigned by IANA, so
        // it should be impossible to connect.
        int nonExistingPort = 293;
        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + nonExistingPort + "/echo");

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
            wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));

        Throwable cause = failure.getCause();
        assertThat(cause, instanceOf(ConnectException.class));
        assertThat(cause.getMessage(), containsStringIgnoringCase("Connection refused"));
    }

    @Test
    public void testWebSocketNotFound() throws Exception
    {
        startServer(container ->
        {});
        startClient(clientConnector -> List.of(new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector))));

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/nothing");

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
            wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));

        Throwable cause = failure.getCause();
        assertThat(cause, instanceOf(UpgradeException.class));
        assertThat(cause.getMessage(), containsStringIgnoringCase("Unexpected HTTP Response Status Code: 404"));
    }

    @Test
    public void testNotNegotiated() throws Exception
    {
        startServer(container -> container.addMapping("/null", (rq, rs, cb) ->
        {
            Response.writeError(rq, rs, cb, HttpStatus.SERVICE_UNAVAILABLE_503);
            return null;
        }));
        startClient(clientConnector -> List.of(new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector))));

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/null");

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
            wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));

        Throwable cause = failure.getCause();
        assertThat(cause, instanceOf(UpgradeException.class));
        assertThat(cause.getMessage(), containsStringIgnoringCase("Unexpected HTTP Response Status Code: 503"));
    }

    @Test
    public void testThrowFromCreator() throws Exception
    {
        startServer(container -> container.addMapping("/throw", (rq, rs, cb) ->
        {
            throw new RuntimeException("throwing from creator");
        }));
        startClient(clientConnector -> List.of(new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector))));
        HTTP2Client http2Client = wsClient.getContainedBeans(HTTP2Client.class).stream().findAny().orElseThrow();

        CountDownLatch latch = new CountDownLatch(1);
        onComplete = latch::countDown;
        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/throw");

        ExecutionException failure;
        try (StacklessLogging ignored = new StacklessLogging(Response.class))
        {
            failure = assertThrows(ExecutionException.class, () ->
                wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));
        }

        Throwable cause = failure.getCause();
        assertThat(cause, instanceOf(UpgradeException.class));
        assertThat(cause.getMessage(), containsStringIgnoringCase("Unexpected HTTP Response Status Code: 500"));

        // Wait for the request to complete on server before stopping.
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var session = http2Client.getBean(SessionContainer.class).getSessions().stream().findAny().orElseThrow();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(session.getStreams(), is(empty())));
    }

    @Test
    public void testServerConnectionClose() throws Exception
    {
        startServer(container -> container.addMapping("/close", (rq, rs, cb) ->
        {
            rq.getConnectionMetaData().getConnection().getEndPoint().close();
            return new EchoSocket();
        }));
        startClient(clientConnector -> List.of(new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector))));

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/close");

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
            wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS));

        Throwable cause = failure.getCause();
        assertThat(cause, instanceOf(ClosedChannelException.class));
    }

    @Test
    public void testServerTimeout() throws Exception
    {
        // Set up idle timeout.
        long serverIdleTimeout = 1000;
        EchoSocket serverEndpoint = new EchoSocket();
        startServer(container ->
        {
            container.addMapping("/specialEcho", (rq, rs, cb) -> serverEndpoint);
            container.setIdleTimeout(Duration.ofMillis(serverIdleTimeout));
        });

        startClient(clientConnector -> List.of(new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector))));
        wsClient.setIdleTimeout(Duration.ZERO);

        // Setup a websocket connection.
        EventSocket clientEndpoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/specialEcho");
        Session session = wsClient.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);
        session.sendText("hello world", Callback.NOOP);
        String received = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(received, equalTo("hello world"));

        // Wait for timeout on server.
        assertTrue(serverEndpoint.closeLatch.await(serverIdleTimeout * 2, TimeUnit.MILLISECONDS));
        assertThat(serverEndpoint.closeCode, equalTo(StatusCode.SHUTDOWN));
        assertThat(serverEndpoint.closeReason, containsStringIgnoringCase("timeout"));
        assertNotNull(serverEndpoint.error);

        // Wait for timeout on client.
        assertTrue(clientEndpoint.closeLatch.await(serverIdleTimeout * 2, TimeUnit.MILLISECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.SHUTDOWN));
        assertThat(clientEndpoint.closeReason, containsStringIgnoringCase("timeout"));
        assertNull(clientEndpoint.error);
    }

    @Test
    public void testHTTP2DisabledFallbackToHTTP1() throws Exception
    {
        startServer(container -> container.addMapping("/echo", (rq, rs, cb) ->
        {
            assertThat(rq.getConnectionMetaData().getHttpVersion(), equalTo(HttpVersion.HTTP_1_1));
            return new EchoSocket();
        }));
        AbstractHTTP2ServerConnectionFactory h2c = connector.getBean(AbstractHTTP2ServerConnectionFactory.class);
        h2c.setConnectProtocolEnabled(false);

        startClient(clientConnector -> List.of(new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)), HttpClientConnectionFactory.HTTP11));

        EventSocket clientEndpoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/echo");
        Session session = wsClient.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);

        String text = "websocket";
        session.sendText(text, Callback.NOOP);

        String message = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals(text, message);

        session.close(StatusCode.NORMAL, null, Callback.NOOP);
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertEquals(StatusCode.NORMAL, clientEndpoint.closeCode);
    }

    @Test
    public void testHTTP2DisabledButForced() throws Exception
    {
        startServer(container -> container.addMapping("/echo", (rq, rs, cb) -> new EchoSocket()));
        AbstractHTTP2ServerConnectionFactory h2c = connector.getBean(AbstractHTTP2ServerConnectionFactory.class);
        h2c.setConnectProtocolEnabled(false);

        startClient(clientConnector -> List.of(new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector)), HttpClientConnectionFactory.HTTP11));

        EventSocket clientEndpoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/echo");
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest(uri);
        // Force WebSocket upgrade with HTTP/2.
        upgradeRequest.setHttpVersion(HttpVersion.HTTP_2.asString());

        ExecutionException failure = assertThrows(ExecutionException.class, () -> wsClient.connect(clientEndpoint, upgradeRequest).get(5, TimeUnit.SECONDS));
        Throwable cause1 = failure.getCause();
        assertThat(cause1, instanceOf(UpgradeException.class));
        // The WebSocket API UpgradeException wraps a WebSocket core UpgradeException, which wraps the original cause.
        Throwable cause2 = cause1.getCause().getCause();
        assertThat(cause2, instanceOf(HttpRequestException.class));
    }

    @Test
    public void testNetworkConnectionLimit() throws Exception
    {
        prepareServer(container -> container.addMapping("/echo", (rq, rs, cb) -> new EchoSocket()));

        int maxNetworkConnectionCount = 5;
        NetworkConnectionLimit networkConnectionLimit = new NetworkConnectionLimit(maxNetworkConnectionCount, connector, tlsConnector);
        connector.addBean(networkConnectionLimit);
        tlsConnector.addBean(networkConnectionLimit);

        server.start();

        startClient(clientConnector -> List.of(new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector))));

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/echo");
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

    @Test
    public void testWebSocketOverHTTP2ConnectResponseDoesNotHaveContentLength() throws Exception
    {
        startServer(container -> container.addMapping("/echo", (rq, rs, cb) -> new EchoSocket()));
        startClient(clientConnector -> List.of(new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector))));

        EventSocket clientEndpoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/echo");
        AtomicReference<org.eclipse.jetty.client.Response> responseRef = new AtomicReference<>();
        wsClient.connect(clientEndpoint, uri, new JettyUpgradeListener()
        {
            @Override
            public void onHandshakeResponse(org.eclipse.jetty.client.Request request, org.eclipse.jetty.client.Response response)
            {
                responseRef.set(response);
            }
        }).get(5, TimeUnit.SECONDS);

        var response = await().atMost(5, TimeUnit.SECONDS).until(responseRef::get, notNullValue());

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getHeaders().getField(HttpHeader.CONTENT_LENGTH), nullValue());
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
}
