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

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PermessageDeflateBufferTest
{
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;

    // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
    private static final List<String> DICT = Arrays.asList(
        "\uD83C\uDF09",
        "\uD83C\uDF0A",
        "\uD83C\uDF0B",
        "\uD83C\uDF0C",
        "\uD83C\uDF0D",
        "\uD83C\uDF0F",
        "\uD83C\uDFC0",
        "\uD83C\uDFC1",
        "\uD83C\uDFC2",
        "\uD83C\uDFC3",
        "\uD83C\uDFC4",
        "\uD83C\uDFC5"
    );

    private static String randomText()
    {
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15000; i++)
        {
            sb.append(DICT.get(rnd.nextInt(DICT.size())));
        }
        return sb.toString();
    }

    private static ByteBuffer randomBytes(int size)
    {
        var bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return BufferUtil.toBuffer(bytes);
    }

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);
        JettyWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
        {
            container.setMaxTextMessageSize(65535);
            container.setInputBufferSize(16384);
            container.addMapping("/", ServerSocket.class);
        });

        server.start();
        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        client.stop();
        server.stop();
    }

    @WebSocket
    public static class ServerSocket extends EchoSocket
    {
        @Override
        public void onError(Throwable cause)
        {
            cause.printStackTrace();
            super.onError(cause);
        }
    }

    @Test
    public void testPermessageDeflateAggregation() throws Exception
    {
        EventSocket socket = new EventSocket();
        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        clientUpgradeRequest.addExtensions("permessage-deflate");

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        Session session = client.connect(socket, uri, clientUpgradeRequest).get(5, TimeUnit.SECONDS);

        String s = randomText();
        session.getRemote().sendString(s);
        assertThat(socket.textMessages.poll(5, TimeUnit.SECONDS), is(s));

        session.close();
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(socket.closeCode, equalTo(StatusCode.NORMAL));
    }

    @Test
    public void testPermessageDeflateFragmentedBinaryMessage() throws Exception
    {
        EventSocket socket = new EventSocket();
        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        clientUpgradeRequest.addExtensions("permessage-deflate");

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        Session session = client.connect(socket, uri, clientUpgradeRequest).get(5, TimeUnit.SECONDS);

        ByteBuffer message = randomBytes(1024);
        session.setMaxFrameSize(64);
        session.getRemote().sendBytes(message);
        assertThat(socket.binaryMessages.poll(5, TimeUnit.SECONDS), equalTo(message));

        session.close();
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(socket.closeCode, equalTo(StatusCode.NORMAL));
    }
}
