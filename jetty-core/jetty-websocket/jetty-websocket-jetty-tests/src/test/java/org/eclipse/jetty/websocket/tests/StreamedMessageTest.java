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

import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreamedMessageTest
{
    private Server _server;
    private WebSocketClient _client;
    private ServerConnector _connector;

    public void start(Supplier<Object> serverEndpointSupplier) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(_server, container ->
            container.addMapping("/", (rq, rs, cb) ->
                serverEndpointSupplier.get()));

        _server.setHandler(wsHandler);
        _server.start();

        _client = new WebSocketClient();
        _client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    public interface BinaryOnMessage
    {
        void accept(Session session, InputStream inputStream) throws Exception;
    }

    @WebSocket()
    public record TestSocket(BinaryOnMessage onMessage)
    {
        @OnWebSocketMessage
        public void onMessage(Session session, InputStream inputStream) throws Exception
        {
            onMessage.accept(session, inputStream);
        }
    }

    @Test
    public void testIncompleteReadAndClose() throws Exception
    {
        start(() -> new TestSocket((session, inputStream) ->
        {
            int read = inputStream.read();
            assertThat(read, greaterThan(-1));
            inputStream.close();
            session.sendText("success", Callback.NOOP);
        }));

        URI uri = new URI("ws://localhost:" + _connector.getLocalPort());
        EventSocket clientEndpoint = new EventSocket();
        Session session = _client.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);

        for (int i = 0; i < 3; i++)
        {
            session.sendPartialBinary(BufferUtil.toBuffer("hello"), false, Callback.NOOP);
            session.sendPartialBinary(BufferUtil.toBuffer(" world"), false, Callback.NOOP);
            session.sendPartialBinary(BufferUtil.EMPTY_BUFFER, true, Callback.NOOP);
            assertThat(clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS), equalTo("success"));
        }

        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
    }

    @Test
    public void testIncompleteRead() throws Exception
    {
        start(() -> new TestSocket((session, inputStream) ->
        {
            int read = inputStream.read();
            assertThat(read, greaterThan(-1));
            session.sendText("success", Callback.NOOP);
        }));

        URI uri = new URI("ws://localhost:" + _connector.getLocalPort());
        EventSocket clientEndpoint = new EventSocket();
        Session session = _client.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);

        for (int i = 0; i < 3; i++)
        {
            session.sendPartialBinary(BufferUtil.toBuffer("hello"), false, Callback.NOOP);
            session.sendPartialBinary(BufferUtil.toBuffer(" world"), false, Callback.NOOP);
            session.sendPartialBinary(BufferUtil.EMPTY_BUFFER, true, Callback.NOOP);
            assertThat(clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS), equalTo("success"));
        }

        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
    }
}