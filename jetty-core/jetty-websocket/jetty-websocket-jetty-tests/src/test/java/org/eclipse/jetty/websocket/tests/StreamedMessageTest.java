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
import java.io.Reader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreamedMessageTest
{
    private Server _server;
    private WebSocketClient _client;
    private ServerConnector _connector;
    private Object _serverEndpoint;

    public void start(Supplier<Object> serverEndpointSupplier) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _serverEndpoint = serverEndpointSupplier.get();
        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(_server, container ->
            container.addMapping("/", (rq, rs, cb) -> _serverEndpoint
            ));

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

    @WebSocket(autoDemand = false)
    public static class DemandingStreamSocket
    {
        @OnWebSocketMessage
        public void onMessage(Session session, InputStream inputStream) throws Exception
        {
            session.sendBinary(BufferUtil.toBuffer(inputStream.readAllBytes()), Callback.NOOP);
            session.demand();
        }

        @OnWebSocketMessage
        public void onMessage(Session session, Reader reader) throws Exception
        {
            session.sendText(IO.toString(reader), Callback.NOOP);
            session.demand();
        }
    }

    @WebSocket()
    public static class AutoDemandingStreamSocket
    {
        @OnWebSocketMessage
        public void onMessage(Session session, InputStream inputStream) throws Exception
        {
            session.sendBinary(BufferUtil.toBuffer(inputStream.readAllBytes()), Callback.NOOP);
        }

        @OnWebSocketMessage
        public void onMessage(Session session, Reader reader) throws Exception
        {
            session.sendText(IO.toString(reader), Callback.NOOP);
        }
    }

    @Test
    public void testAutoDemandingStreams() throws Exception
    {
        start(AutoDemandingStreamSocket::new);
        URI uri = new URI("ws://localhost:" + _connector.getLocalPort());
        EventSocket clientEndpoint = new EventSocket();
        Session session = _client.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);

        testBinaryEcho(clientEndpoint);
        testBinaryEcho(clientEndpoint);

        testTextEcho(clientEndpoint);
        testTextEcho(clientEndpoint);

        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
    }

    @Test
    public void testDemandingStreams() throws Exception
    {
        start(DemandingStreamSocket::new);
        URI uri = new URI("ws://localhost:" + _connector.getLocalPort());
        EventSocket clientEndpoint = new EventSocket();
        Session session = _client.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);

        testBinaryEcho(clientEndpoint);
        testBinaryEcho(clientEndpoint);

        testTextEcho(clientEndpoint);
        testTextEcho(clientEndpoint);

        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
    }

    private void testBinaryEcho(EventSocket clientEndpoint) throws Exception
    {
        Session session = clientEndpoint.session;
        session.sendPartialBinary(BufferUtil.toBuffer("hello"), false, Callback.NOOP);
        session.sendPartialBinary(BufferUtil.toBuffer(" world"), false, Callback.NOOP);
        session.sendPartialBinary(BufferUtil.toBuffer(" 123"), false, Callback.NOOP);
        session.sendPartialBinary(BufferUtil.toBuffer("4"), false, Callback.NOOP);

        // We will not see the message until the final frame is sent with fin=true.
        assertNull(clientEndpoint.binaryMessages.poll(500, TimeUnit.MILLISECONDS));

        session.sendPartialBinary(BufferUtil.EMPTY_BUFFER, true, Callback.NOOP);
        ByteBuffer received = clientEndpoint.binaryMessages.poll(5, TimeUnit.SECONDS);
        assertThat(BufferUtil.toString(received), equalTo("hello world 1234"));
    }

    private void testTextEcho(EventSocket clientEndpoint) throws Exception
    {
        Session session = clientEndpoint.session;
        session.sendPartialText("hello", false, Callback.NOOP);
        session.sendPartialText(" world", false, Callback.NOOP);
        session.sendPartialText(" 123", false, Callback.NOOP);
        session.sendPartialText("4", false, Callback.NOOP);

        // We will not see the message until the final frame is sent with fin=true.
        assertNull(clientEndpoint.textMessages.poll(500, TimeUnit.MILLISECONDS));

        session.sendPartialText(null, true, Callback.NOOP);
        String received = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(received, equalTo("hello world 1234"));
    }
}