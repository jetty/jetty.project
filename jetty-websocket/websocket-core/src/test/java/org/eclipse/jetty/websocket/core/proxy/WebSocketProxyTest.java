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

package org.eclipse.jetty.websocket.core.proxy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.FrameHandler.CoreSession;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.internal.WebSocketChannel;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

public class WebSocketProxyTest
{
    private Server _server;
    private WebSocketCoreClient _client;
    private WebSocketProxy proxy;
    private BasicFrameHandler.ServerEchoHandler serverFrameHandler;
    private TestHandler testHandler;

    private class TestHandler extends AbstractHandler
    {
        public void blockServerUpgradeRequests()
        {
            blockServerUpgradeRequests = true;
        }

        public boolean blockServerUpgradeRequests = false;

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (baseRequest.getHeader("Upgrade") != null)
            {
                if (blockServerUpgradeRequests && target.startsWith("/server/"))
                {
                    response.sendError(HttpStatus.INTERNAL_SERVER_ERROR_500);
                    baseRequest.setHandled(true);
                }
            }
        }
    }

    @BeforeEach
    public void start() throws Exception
    {
        _server = new Server();
        ServerConnector connector = new ServerConnector(_server);
        connector.setPort(8080);
        _server.addConnector(connector);

        HandlerList handlers = new HandlerList();
        testHandler = new TestHandler();
        handlers.addHandler(testHandler);

        FrameHandler.ConfigurationCustomizer customizer = new FrameHandler.ConfigurationCustomizer();
        customizer.setIdleTimeout(Duration.ofSeconds(3));

        ContextHandler serverContext = new ContextHandler("/server");
        serverFrameHandler = new BasicFrameHandler.ServerEchoHandler("SERVER");
        WebSocketNegotiator negotiator = WebSocketNegotiator.from((negotiation) -> serverFrameHandler, customizer);
        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler(negotiator);
        serverContext.setHandler(upgradeHandler);
        handlers.addHandler(serverContext);

        _client = new WebSocketCoreClient(null, customizer);
        _client.start();
        URI uri = new URI("ws://localhost:8080/server/");

        ContextHandler proxyContext = new ContextHandler("/proxy");
        proxy = new WebSocketProxy(_client, uri);
        negotiator = WebSocketNegotiator.from((negotiation) -> proxy.client2Proxy, customizer);
        upgradeHandler = new WebSocketUpgradeHandler(negotiator);
        proxyContext.setHandler(upgradeHandler);
        handlers.addHandler(proxyContext);

        _server.setHandler(handlers);
        _server.start();
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
        {
            throw new TimeoutException("client2Proxy close timeout");
        }

        if (server2Proxy != null && !server2Proxy.closed.await(5, TimeUnit.SECONDS))
        {
            throw new TimeoutException("server2Proxy close timeout");
        }
    }

    @Test
    public void testEcho() throws Exception
    {
        BasicFrameHandler clientHandler = new BasicFrameHandler("CLIENT");
        WebSocketProxy.Client2Proxy proxyClientSide = proxy.client2Proxy;
        WebSocketProxy.Server2Proxy proxyServerSide = proxy.server2Proxy;

        ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(_client, new URI("ws://localhost:8080/proxy/a"), clientHandler);
        CompletableFuture<CoreSession> response = _client.connect(upgradeRequest);
        response.get(5, TimeUnit.SECONDS);
        clientHandler.sendText("hello world");
        clientHandler.close("standard close");
        clientHandler.awaitClose();
        serverFrameHandler.awaitClose();
        awaitProxyClose(proxyClientSide, proxyServerSide);

        assertThat(proxyClientSide.getState(), is(WebSocketProxy.State.CLOSED));
        assertThat(proxyServerSide.getState(), is(WebSocketProxy.State.CLOSED));

        assertThat(proxyClientSide.receivedFrames.poll().getPayloadAsUTF8(), is("hello world"));
        assertThat(serverFrameHandler.receivedFrames.poll().getPayloadAsUTF8(), is("hello world"));
        assertThat(proxyServerSide.receivedFrames.poll().getPayloadAsUTF8(), is("hello world"));
        assertThat(clientHandler.receivedFrames.poll().getPayloadAsUTF8(), is("hello world"));

        assertThat(CloseStatus.getCloseStatus(proxyClientSide.receivedFrames.poll()).getReason(), is("standard close"));
        assertThat(CloseStatus.getCloseStatus(serverFrameHandler.receivedFrames.poll()).getReason(), is("standard close"));
        assertThat(CloseStatus.getCloseStatus(proxyServerSide.receivedFrames.poll()).getReason(), is("standard close"));
        assertThat(CloseStatus.getCloseStatus(clientHandler.receivedFrames.poll()).getReason(), is("standard close"));

        assertNull(proxyClientSide.receivedFrames.poll());
        assertNull(serverFrameHandler.receivedFrames.poll());
        assertNull(proxyServerSide.receivedFrames.poll());
        assertNull(clientHandler.receivedFrames.poll());
    }

    @Test
    public void testFailServerUpgrade() throws Exception
    {
        testHandler.blockServerUpgradeRequests();
        WebSocketProxy.Client2Proxy proxyClientSide = proxy.client2Proxy;
        WebSocketProxy.Server2Proxy proxyServerSide = proxy.server2Proxy;

        BasicFrameHandler clientHandler = new BasicFrameHandler("CLIENT");
        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketChannel.class))
        {
            CompletableFuture<CoreSession> response = _client.connect(clientHandler, new URI("ws://localhost:8080/proxy/"));
            response.get(5, TimeUnit.SECONDS);
            clientHandler.sendText("hello world");
            clientHandler.close("standard close");
            clientHandler.awaitClose();
            awaitProxyClose(proxyClientSide, null);
        }

        assertNull(proxyClientSide.receivedFrames.poll());
        assertThat(proxyClientSide.getState(), is(WebSocketProxy.State.FAILED));

        assertNull(proxyServerSide.receivedFrames.poll());
        assertThat(proxyServerSide.getState(), is(WebSocketProxy.State.FAILED));

        assertFalse(serverFrameHandler.opened.await(250, TimeUnit.MILLISECONDS));

        CloseStatus closeStatus = CloseStatus.getCloseStatus(clientHandler.receivedFrames.poll());
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), containsString("Failed to upgrade to websocket: Unexpected HTTP Response Status Code:"));
    }


    @Test
    public void testClientError() throws Exception
    {
        BasicFrameHandler clientHandler = new BasicFrameHandler("CLIENT")
        {
            @Override
            public void onOpen(CoreSession coreSession, Callback callback)
            {
                System.err.println(name + " onOpen(): " + coreSession);
                throw new IllegalStateException("simulated client onOpen error");
            }
        };
        WebSocketProxy.Client2Proxy proxyClientSide = proxy.client2Proxy;
        WebSocketProxy.Server2Proxy proxyServerSide = proxy.server2Proxy;

        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketChannel.class))
        {
            CompletableFuture<CoreSession> response = _client.connect(clientHandler, new URI("ws://localhost:8080/proxy/"));
            response.get(5, TimeUnit.SECONDS);
            clientHandler.awaitClose();
            serverFrameHandler.awaitClose();
            awaitProxyClose(proxyClientSide, proxyServerSide);
        }

        CloseStatus closeStatus = CloseStatus.getCloseStatus(proxyClientSide.receivedFrames.poll());
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), containsString("simulated client onOpen error"));
        assertThat(proxyClientSide.getState(), is(WebSocketProxy.State.CLOSED));

        closeStatus = CloseStatus.getCloseStatus(proxyServerSide.receivedFrames.poll());
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), containsString("simulated client onOpen error"));
        assertThat(proxyServerSide.getState(), is(WebSocketProxy.State.CLOSED));

        closeStatus = CloseStatus.getCloseStatus(serverFrameHandler.receivedFrames.poll());
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), containsString("simulated client onOpen error"));

        assertNull(clientHandler.receivedFrames.poll());
    }



    @Test
    public void testServerError() throws Exception
    {
        serverFrameHandler.throwOnFrame();
        WebSocketProxy.Client2Proxy proxyClientSide = proxy.client2Proxy;
        WebSocketProxy.Server2Proxy proxyServerSide = proxy.server2Proxy;

        BasicFrameHandler clientHandler = new BasicFrameHandler("CLIENT");
        ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(_client, new URI("ws://localhost:8080/proxy/test"), clientHandler);

        CompletableFuture<CoreSession> response = _client.connect(upgradeRequest);
        response.get(5, TimeUnit.SECONDS);
        clientHandler.sendText("hello world");
        clientHandler.awaitClose();
        serverFrameHandler.awaitClose();
        awaitProxyClose(proxyClientSide, proxyServerSide);

        CloseStatus closeStatus;
        Frame frame;

        // Client
        frame = clientHandler.receivedFrames.poll();
        assertThat(CloseStatus.getCloseStatus(frame).getCode(), is(CloseStatus.SERVER_ERROR));

        // Client2Proxy
        frame = proxyClientSide.receivedFrames.poll();
        assertThat(frame.getOpCode(), is(OpCode.TEXT));
        assertThat(frame.getPayloadAsUTF8(), is("hello world"));

        frame = proxyClientSide.receivedFrames.poll();
        closeStatus = CloseStatus.getCloseStatus(frame);
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), is("intentionally throwing in server onFrame()"));

        frame = proxyClientSide.receivedFrames.poll();
        assertNull(frame);
        assertThat(proxyClientSide.getState(), is(WebSocketProxy.State.CLOSED));

        // Server2Proxy
        frame = proxyServerSide.receivedFrames.poll();
        closeStatus = CloseStatus.getCloseStatus(frame);
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), is("intentionally throwing in server onFrame()"));

        frame = proxyServerSide.receivedFrames.poll();
        assertNull(frame);
        assertThat(proxyServerSide.getState(), is(WebSocketProxy.State.CLOSED));

        // Server
        frame = serverFrameHandler.receivedFrames.poll();
        assertThat(frame.getOpCode(), is(OpCode.TEXT));
        assertThat(frame.getPayloadAsUTF8(), is("hello world"));
        frame = serverFrameHandler.receivedFrames.poll();
        assertNull(frame);
    }

    @Test
    public void testServerErrorClientNoResponse() throws Exception
    {
        serverFrameHandler.throwOnFrame();
        WebSocketProxy.Client2Proxy proxyClientSide = proxy.client2Proxy;
        WebSocketProxy.Server2Proxy proxyServerSide = proxy.server2Proxy;

        BasicFrameHandler clientHandler = new BasicFrameHandler("CLIENT")
        {
            @Override
            public void onFrame(Frame frame, Callback callback)
            {
                System.err.println(name + " onFrame(): " + frame);
                receivedFrames.offer(Frame.copy(frame));
            }
        };
        ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(_client, new URI("ws://localhost:8080/proxy/test"), clientHandler);

        CompletableFuture<CoreSession> response = _client.connect(upgradeRequest);
        response.get(5, TimeUnit.SECONDS);

        clientHandler.sendText("hello world");

        clientHandler.awaitClose();
        serverFrameHandler.awaitClose();
        awaitProxyClose(proxyClientSide, proxyServerSide);

        CloseStatus closeStatus;
        Frame frame;

        // Client
        frame = clientHandler.receivedFrames.poll();
        closeStatus = CloseStatus.getCloseStatus(frame);
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), is("intentionally throwing in server onFrame()"));
        frame = clientHandler.receivedFrames.poll();
        assertNull(frame);

        // Client2Proxy
        frame = proxyClientSide.receivedFrames.poll();
        assertThat(frame.getOpCode(), is(OpCode.TEXT));
        assertThat(frame.getPayloadAsUTF8(), is("hello world"));

        frame = proxyClientSide.receivedFrames.poll();
        assertNull(frame);
        assertThat(proxyClientSide.getState(), is(WebSocketProxy.State.FAILED));

        // Server2Proxy
        frame = proxyServerSide.receivedFrames.poll();
        closeStatus = CloseStatus.getCloseStatus(frame);
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), is("intentionally throwing in server onFrame()"));

        frame = proxyServerSide.receivedFrames.poll();
        assertNull(frame);
        assertThat(proxyServerSide.getState(), is(WebSocketProxy.State.FAILED));

        // Server
        frame = serverFrameHandler.receivedFrames.poll();
        assertThat(frame.getOpCode(), is(OpCode.TEXT));
        assertThat(frame.getPayloadAsUTF8(), is("hello world"));
        frame = serverFrameHandler.receivedFrames.poll();
        assertNull(frame);
    }
}
