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

package org.eclipse.jetty.websocket.tests.listeners;

import java.io.Reader;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.tests.EventSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MessageReaderErrorTest
{
    private Server _server;
    private ServerConnector _connector;
    private WebSocketClient _client;
    private WebSocketUpgradeHandler _upgradeHandler;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _upgradeHandler = WebSocketUpgradeHandler.from(_server);
        _server.setHandler(_upgradeHandler);

        _server.start();
        _client = new WebSocketClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @WebSocket
    public static class ReaderErrorEndpoint
    {
        public final int toRead;
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public BlockingQueue<String> textMessages = new BlockingArrayQueue<>();
        public volatile String closeReason;
        public volatile int closeCode = StatusCode.UNDEFINED;
        public volatile Throwable error = null;

        public ReaderErrorEndpoint()
        {
            this(-1);
        }

        public ReaderErrorEndpoint(int read)
        {
            toRead = read;
        }

        @OnWebSocketMessage
        public void onMessage(Reader reader) throws Exception
        {
            if (toRead < 0)
            {
                textMessages.add(IO.toString(reader));
            }
            else
            {
                Utf8StringBuilder sb = new Utf8StringBuilder();
                for (int i = 0; i < toRead; i++)
                {
                    int read = reader.read();
                    if (read < 0)
                        break;
                    sb.append((byte)read);
                }
                textMessages.add(sb.build());
            }

            // This reader will be dispatched to another thread and won't be the thread reading from the connection,
            // however throwing from here should still fail the websocket connection.
            throw new IllegalStateException("failed from test");
        }

        @OnWebSocketError
        public void onError(Throwable t)
        {
            error = t;
        }

        @OnWebSocketClose
        public void onClose(int code, String reason)
        {
            closeCode = code;
            closeReason = reason;
            closeLatch.countDown();
        }
    }

    @Test
    public void testReaderOnError() throws Exception
    {
        ReaderErrorEndpoint serverEndpoint = new ReaderErrorEndpoint();
        _upgradeHandler.getServerWebSocketContainer()
            .addMapping("/", (req, resp, cb) -> serverEndpoint);

        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());
        EventSocket clientEndpoint = new EventSocket();
        Session session = _client.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);
        session.sendPartialText("hel", false, Callback.NOOP);
        session.sendPartialText("lo ", false, Callback.NOOP);
        session.sendPartialText("wor", false, Callback.NOOP);
        session.sendPartialText("ld", false, Callback.NOOP);
        session.sendPartialText(null, true, Callback.NOOP);

        assertThat(serverEndpoint.textMessages.poll(5, TimeUnit.SECONDS), equalTo("hello world"));

        assertTrue(serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverEndpoint.closeCode, equalTo(StatusCode.SERVER_ERROR));
        assertThat(serverEndpoint.closeReason, containsString("failed from test"));
        assertThat(serverEndpoint.error, instanceOf(IllegalStateException.class));

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.SERVER_ERROR));
        assertThat(clientEndpoint.closeReason, containsString("failed from test"));
        assertNull(clientEndpoint.error);
    }

    @Test
    public void testReaderOnErrorPartialRead() throws Exception
    {
        ReaderErrorEndpoint serverEndpoint = new ReaderErrorEndpoint(5);
        _upgradeHandler.getServerWebSocketContainer()
            .addMapping("/", (req, resp, cb) -> serverEndpoint);

        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());
        EventSocket clientEndpoint = new EventSocket();
        Session session = _client.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);
        session.sendPartialText("hel", false, Callback.NOOP);
        session.sendPartialText("lo ", false, Callback.NOOP);
        session.sendPartialText("wor", false, Callback.NOOP);
        session.sendPartialText("ld", false, Callback.NOOP);
        session.sendPartialText(null, true, Callback.NOOP);

        assertThat(serverEndpoint.textMessages.poll(5, TimeUnit.SECONDS), equalTo("hello"));

        assertTrue(serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverEndpoint.closeCode, equalTo(StatusCode.SERVER_ERROR));
        assertThat(serverEndpoint.closeReason, containsString("failed from test"));
        assertThat(serverEndpoint.error, instanceOf(IllegalStateException.class));

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.SERVER_ERROR));
        assertThat(clientEndpoint.closeReason, containsString("failed from test"));
        assertNull(clientEndpoint.error);
    }
}
