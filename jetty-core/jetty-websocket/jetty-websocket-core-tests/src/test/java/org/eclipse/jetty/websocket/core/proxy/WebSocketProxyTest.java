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

package org.eclipse.jetty.websocket.core.proxy;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.EchoFrameHandler;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.TestAsyncFrameHandler;
import org.eclipse.jetty.websocket.core.TestWebSocketNegotiator;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketProxyTest
{
    private Server _server;
    private WebSocketCoreClient _client;
    private WebSocketProxy proxy;
    private EchoFrameHandler serverFrameHandler;
    private TestHandler testHandler;
    private Configuration.ConfigurationCustomizer defaultCustomizer;
    private URI proxyUri;

    private static class TestHandler extends Handler.Abstract
    {
        public void blockServerUpgradeRequests()
        {
            blockServerUpgradeRequests = true;
        }

        public boolean blockServerUpgradeRequests = false;

        @Override
        public void process(Request request, Response response, Callback callback)
        {
            if (request.getHeaders().get("Upgrade") != null)
            {
                if (blockServerUpgradeRequests && Request.getPathInContext(request).startsWith("/server"))
                {
                    request.accept();
                    Response.writeError(request, response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500);
                }
            }
        }
    }

    @BeforeEach
    public void start() throws Exception
    {
        _server = new Server();
        ServerConnector connector = new ServerConnector(_server);
        _server.addConnector(connector);

        Handler.Collection handlers = new Handler.Collection();
        testHandler = new TestHandler();
        handlers.addHandler(testHandler);

        defaultCustomizer = new Configuration.ConfigurationCustomizer();
        defaultCustomizer.setIdleTimeout(Duration.ofSeconds(3));

        ContextHandler serverContext = new ContextHandler("/server");
        serverFrameHandler = new EchoFrameHandler("SERVER");
        WebSocketNegotiator negotiator = new TestWebSocketNegotiator(serverFrameHandler, defaultCustomizer);
        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler();
        upgradeHandler.addMapping("/*", negotiator);
        serverContext.setHandler(upgradeHandler);
        handlers.addHandler(serverContext);

        ContextHandler proxyContext = new ContextHandler("/proxy");
        negotiator = new WebSocketNegotiator.AbstractNegotiator(defaultCustomizer)
        {
            @Override
            public FrameHandler negotiate(ServerUpgradeRequest request, ServerUpgradeResponse response, Callback callback)
            {
                return proxy.client2Proxy;
            }
        };
        upgradeHandler = new WebSocketUpgradeHandler();
        upgradeHandler.addMapping("/*", negotiator);
        proxyContext.setHandler(upgradeHandler);
        handlers.addHandler(proxyContext);

        _server.setHandler(handlers);
        _server.start();
        _client = new WebSocketCoreClient();
        _client.start();

        URI uri = new URI("ws://localhost:" + connector.getLocalPort());
        proxyUri = uri.resolve("/proxy");
        proxy = new WebSocketProxy(_client, uri.resolve("/server"));
    }

    @AfterEach
    public void stop() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    public void awaitProxyClose(WebSocketProxy.Client2Proxy client2Proxy, WebSocketProxy.Server2Proxy server2Proxy) throws Exception
    {
        if (client2Proxy != null && !client2Proxy.closed.await(5, TimeUnit.SECONDS))
            throw new TimeoutException("client2Proxy close timeout");

        if (server2Proxy != null && !server2Proxy.closed.await(5, TimeUnit.SECONDS))
            throw new TimeoutException("server2Proxy close timeout");
    }

    @Test
    public void testEcho() throws Exception
    {
        TestAsyncFrameHandler clientFrameHandler = new TestAsyncFrameHandler("CLIENT");
        WebSocketProxy.Client2Proxy proxyClientSide = proxy.client2Proxy;
        WebSocketProxy.Server2Proxy proxyServerSide = proxy.server2Proxy;

        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(_client, proxyUri, clientFrameHandler);
        upgradeRequest.setConfiguration(defaultCustomizer);
        CompletableFuture<CoreSession> response = _client.connect(upgradeRequest);

        response.get(5, TimeUnit.SECONDS);
        clientFrameHandler.sendText("hello world");
        clientFrameHandler.close(CloseStatus.NORMAL, "standard close");
        assertTrue(clientFrameHandler.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverFrameHandler.closeLatch.await(5, TimeUnit.SECONDS));
        awaitProxyClose(proxyClientSide, proxyServerSide);

        assertThat(proxyClientSide.getState(), is(WebSocketProxy.State.CLOSED));
        assertThat(proxyServerSide.getState(), is(WebSocketProxy.State.CLOSED));

        assertThat(Objects.requireNonNull(proxyClientSide.receivedFrames.poll()).getPayloadAsUTF8(), is("hello world"));
        assertThat(Objects.requireNonNull(serverFrameHandler.receivedFrames.poll()).getPayloadAsUTF8(), is("hello world"));
        assertThat(Objects.requireNonNull(proxyServerSide.receivedFrames.poll()).getPayloadAsUTF8(), is("hello world"));
        assertThat(Objects.requireNonNull(clientFrameHandler.receivedFrames.poll()).getPayloadAsUTF8(), is("hello world"));

        assertThat(CloseStatus.getCloseStatus(proxyClientSide.receivedFrames.poll()).getReason(), is("standard close"));
        assertThat(CloseStatus.getCloseStatus(serverFrameHandler.receivedFrames.poll()).getReason(), is("standard close"));
        assertThat(CloseStatus.getCloseStatus(proxyServerSide.receivedFrames.poll()).getReason(), is("standard close"));
        assertThat(CloseStatus.getCloseStatus(clientFrameHandler.receivedFrames.poll()).getReason(), is("standard close"));

        assertNull(proxyClientSide.receivedFrames.poll());
        assertNull(serverFrameHandler.receivedFrames.poll());
        assertNull(proxyServerSide.receivedFrames.poll());
        assertNull(clientFrameHandler.receivedFrames.poll());
    }

    @Test
    public void testFailServerUpgrade() throws Exception
    {
        testHandler.blockServerUpgradeRequests();
        WebSocketProxy.Client2Proxy proxyClientSide = proxy.client2Proxy;
        WebSocketProxy.Server2Proxy proxyServerSide = proxy.server2Proxy;

        TestAsyncFrameHandler clientFrameHandler = new TestAsyncFrameHandler("CLIENT");
        try (StacklessLogging ignored = new StacklessLogging(WebSocketCoreSession.class))
        {
            CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(_client, proxyUri, clientFrameHandler);
            upgradeRequest.setConfiguration(defaultCustomizer);
            CompletableFuture<CoreSession> response = _client.connect(upgradeRequest);
            response.get(5, TimeUnit.SECONDS);
            clientFrameHandler.sendText("hello world");
            clientFrameHandler.close();
            assertTrue(clientFrameHandler.closeLatch.await(5, TimeUnit.SECONDS));
            awaitProxyClose(proxyClientSide, null);
        }

        assertNull(proxyClientSide.receivedFrames.poll());
        assertThat(proxyClientSide.getState(), is(WebSocketProxy.State.FAILED));

        assertNull(proxyServerSide.receivedFrames.poll());
        assertThat(proxyServerSide.getState(), is(WebSocketProxy.State.FAILED));

        assertFalse(serverFrameHandler.openLatch.await(250, TimeUnit.MILLISECONDS));

        CloseStatus closeStatus = CloseStatus.getCloseStatus(clientFrameHandler.receivedFrames.poll());
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), containsString("Failed to upgrade to websocket: Unexpected HTTP Response"));
    }

    @Test
    public void testClientError() throws Exception
    {
        TestAsyncFrameHandler clientFrameHandler = new TestAsyncFrameHandler("CLIENT")
        {
            @Override
            public void onOpen(CoreSession coreSession, Callback callback)
            {
                throw new IllegalStateException("simulated client onOpen error");
            }
        };
        WebSocketProxy.Client2Proxy proxyClientSide = proxy.client2Proxy;
        WebSocketProxy.Server2Proxy proxyServerSide = proxy.server2Proxy;

        try (StacklessLogging ignored = new StacklessLogging(WebSocketCoreSession.class))
        {
            CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(_client, proxyUri, clientFrameHandler);
            upgradeRequest.setConfiguration(defaultCustomizer);
            CompletableFuture<CoreSession> response = _client.connect(upgradeRequest);
            Exception e = assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS));
            assertThat(e.getMessage(), containsString("simulated client onOpen error"));
            assertTrue(clientFrameHandler.closeLatch.await(5, TimeUnit.SECONDS));
            assertTrue(serverFrameHandler.closeLatch.await(5, TimeUnit.SECONDS));
            awaitProxyClose(proxyClientSide, proxyServerSide);
        }

        CloseStatus closeStatus = CloseStatus.getCloseStatus(proxyClientSide.receivedFrames.poll());
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), containsString("simulated client onOpen error"));
        assertThat(proxyClientSide.getState(), is(WebSocketProxy.State.FAILED));

        closeStatus = CloseStatus.getCloseStatus(serverFrameHandler.receivedFrames.poll());
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), containsString("simulated client onOpen error"));

        assertNull(proxyServerSide.receivedFrames.poll());
        assertNull(clientFrameHandler.receivedFrames.poll());
    }

    @Test
    public void testServerError() throws Exception
    {
        serverFrameHandler.throwOnFrame();
        WebSocketProxy.Client2Proxy proxyClientSide = proxy.client2Proxy;
        WebSocketProxy.Server2Proxy proxyServerSide = proxy.server2Proxy;

        TestAsyncFrameHandler clientFrameHandler = new TestAsyncFrameHandler("CLIENT");
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(_client, proxyUri, clientFrameHandler);
        upgradeRequest.setConfiguration(defaultCustomizer);
        CompletableFuture<CoreSession> response = _client.connect(upgradeRequest);

        response.get(5, TimeUnit.SECONDS);
        clientFrameHandler.sendText("hello world");
        assertTrue(clientFrameHandler.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverFrameHandler.closeLatch.await(5, TimeUnit.SECONDS));
        awaitProxyClose(proxyClientSide, proxyServerSide);

        CloseStatus closeStatus;
        Frame frame;

        // Client2Proxy
        frame = proxyClientSide.receivedFrames.poll();
        assertNotNull(frame);
        assertThat(frame.getOpCode(), is(OpCode.TEXT));
        assertThat(frame.getPayloadAsUTF8(), is("hello world"));

        // Server
        frame = serverFrameHandler.receivedFrames.poll();
        assertNotNull(frame);
        assertThat(frame.getOpCode(), is(OpCode.TEXT));
        assertThat(frame.getPayloadAsUTF8(), is("hello world"));
        frame = serverFrameHandler.receivedFrames.poll();
        assertNull(frame);

        // Server2Proxy
        frame = proxyServerSide.receivedFrames.poll();
        assertNotNull(frame);
        closeStatus = CloseStatus.getCloseStatus(frame);
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), is("intentionally throwing in server onFrame()"));

        // Client
        frame = clientFrameHandler.receivedFrames.poll();
        assertNotNull(frame);
        closeStatus = CloseStatus.getCloseStatus(frame);
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), is("intentionally throwing in server onFrame()"));

        // Client2Proxy receives no close response because is error close
        assertNull(proxyClientSide.receivedFrames.poll());

        // Check Proxy is in expected final state
        assertNull(proxyClientSide.receivedFrames.poll());
        assertNull(proxyServerSide.receivedFrames.poll());
        assertThat(proxyClientSide.getState(), is(WebSocketProxy.State.FAILED));
        assertThat(proxyServerSide.getState(), is(WebSocketProxy.State.FAILED));
    }

    @Test
    public void testServerErrorClientNoResponse() throws Exception
    {
        serverFrameHandler.throwOnFrame();
        WebSocketProxy.Client2Proxy proxyClientSide = proxy.client2Proxy;
        WebSocketProxy.Server2Proxy proxyServerSide = proxy.server2Proxy;

        TestAsyncFrameHandler clientFrameHandler = new TestAsyncFrameHandler("CLIENT")
        {
            @Override
            public void onFrame(Frame frame, Callback callback)
            {
                LOG.info("[{}] onFrame {}", name, frame);
                receivedFrames.offer(Frame.copy(frame));
            }
        };

        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(_client, proxyUri, clientFrameHandler);
        upgradeRequest.setConfiguration(defaultCustomizer);
        CompletableFuture<CoreSession> response = _client.connect(upgradeRequest);
        response.get(5, TimeUnit.SECONDS);
        clientFrameHandler.sendText("hello world");
        assertTrue(clientFrameHandler.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverFrameHandler.closeLatch.await(5, TimeUnit.SECONDS));
        awaitProxyClose(proxyClientSide, proxyServerSide);

        CloseStatus closeStatus;
        Frame frame;

        // Client2Proxy
        frame = proxyClientSide.receivedFrames.poll();
        assertNotNull(frame);
        assertThat(frame.getOpCode(), is(OpCode.TEXT));
        assertThat(frame.getPayloadAsUTF8(), is("hello world"));

        // Server
        frame = serverFrameHandler.receivedFrames.poll();
        assertNotNull(frame);
        assertThat(frame.getOpCode(), is(OpCode.TEXT));
        assertThat(frame.getPayloadAsUTF8(), is("hello world"));
        assertNull(serverFrameHandler.receivedFrames.poll());

        // Server2Proxy
        frame = proxyServerSide.receivedFrames.poll();
        closeStatus = CloseStatus.getCloseStatus(frame);
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), is("intentionally throwing in server onFrame()"));

        // Client
        frame = clientFrameHandler.receivedFrames.poll();
        closeStatus = CloseStatus.getCloseStatus(frame);
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), is("intentionally throwing in server onFrame()"));
        assertNull(clientFrameHandler.receivedFrames.poll());

        // Client2Proxy does NOT receive close response from the client and fails
        assertNull(proxyClientSide.receivedFrames.poll());
        assertThat(proxyClientSide.getState(), is(WebSocketProxy.State.FAILED));

        // Server2Proxy is failed by the Client2Proxy
        assertNull(proxyServerSide.receivedFrames.poll());
        assertThat(proxyServerSide.getState(), is(WebSocketProxy.State.FAILED));
    }
}
