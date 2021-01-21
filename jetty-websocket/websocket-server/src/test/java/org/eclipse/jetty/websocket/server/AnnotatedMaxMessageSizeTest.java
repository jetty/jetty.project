//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.server;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.BlockheadClientRequest;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.server.examples.echo.BigEchoSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static java.time.Duration.ofSeconds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class AnnotatedMaxMessageSizeTest
{
    private static BlockheadClient client;
    private static Server server;
    private static ServerConnector connector;
    private static URI serverUri;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        WebSocketHandler wsHandler = new WebSocketHandler()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                factory.register(BigEchoSocket.class);
            }
        };

        server.setHandler(wsHandler);
        server.start();

        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("ws://%s:%d/", host, port));
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @BeforeAll
    public static void startClient() throws Exception
    {
        client = new BlockheadClient();
        client.setIdleTimeout(TimeUnit.SECONDS.toMillis(2));
        client.start();
    }

    @AfterAll
    public static void stopClient() throws Exception
    {
        client.stop();
    }

    @Test
    public void testEchoGood() throws Exception
    {
        BlockheadClientRequest request = client.newWsRequest(serverUri);
        request.header(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "echo");

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            clientConn.write(new TextFrame().setPayload(msg));

            // Read frame (hopefully text frame)
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame tf = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Text Frame.status code", tf.getPayloadAsUTF8(), is(msg));
        }
    }

    @Test
    public void testEchoTooBig() throws Exception
    {
        BlockheadClientRequest request = client.newWsRequest(serverUri);
        request.header(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "echo");

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT);
             StacklessLogging ignore = new StacklessLogging(Parser.class))
        {
            Assertions.assertTimeoutPreemptively(ofSeconds(8), () ->
            {
                // Generate text frame
                int size = 120 * 1024;
                byte[] buf = new byte[size]; // buffer bigger than maxMessageSize
                Arrays.fill(buf, (byte)'x');
                clientConn.write(new TextFrame().setPayload(ByteBuffer.wrap(buf)));

                // Read frame (hopefully close frame saying its too large)
                LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
                WebSocketFrame tf = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
                assertThat("Frame is close", tf.getOpCode(), is(OpCode.CLOSE));
                CloseInfo close = new CloseInfo(tf);
                assertThat("Close Code", close.getStatusCode(), is(StatusCode.MESSAGE_TOO_LARGE));
            });
        }
    }
}
