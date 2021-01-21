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

package org.eclipse.jetty.websocket.jsr356.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BinaryStreamTest
{
    private static final String PATH = "/echo";

    private Server server;
    private ServerConnector connector;
    private WebSocketContainer wsClient;

    @BeforeEach
    public void prepare() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/", true, false);
        WebSocketServerContainerInitializer.configure(context, (servletContext, container) ->
        {
            ServerEndpointConfig config = ServerEndpointConfig.Builder.create(ServerBinaryStreamer.class, PATH).build();
            container.addEndpoint(config);
        });

        server.start();

        wsClient = ContainerProvider.getWebSocketContainer();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testEchoWithMediumMessage() throws Exception
    {
        testEcho(1024);
    }

    @Test
    public void testLargestMessage() throws Exception
    {
        testEcho(wsClient.getDefaultMaxBinaryMessageBufferSize());
    }

    private void testEcho(int size) throws Exception
    {
        byte[] data = randomBytes(size);
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + PATH);
        ClientBinaryStreamer client = new ClientBinaryStreamer();
        Session session = wsClient.connectToServer(client, uri);

        try (OutputStream output = session.getBasicRemote().getSendStream())
        {
            output.write(data);
        }

        assertTrue(client.await(5, TimeUnit.SECONDS));
        assertArrayEquals(data, client.getEcho());
    }

    @Test
    public void testMoreThanLargestMessageOneByteAtATime() throws Exception
    {
        int size = wsClient.getDefaultMaxBinaryMessageBufferSize() + 16;
        byte[] data = randomBytes(size);
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + PATH);
        ClientBinaryStreamer client = new ClientBinaryStreamer();
        Session session = wsClient.connectToServer(client, uri);

        try (OutputStream output = session.getBasicRemote().getSendStream())
        {
            for (int i = 0; i < size; ++i)
            {
                output.write(data[i]);
            }
        }

        assertTrue(client.await(5, TimeUnit.SECONDS));
        assertArrayEquals(data, client.getEcho());
    }

    @Test
    public void testNotReadingToEndOfStream() throws Exception
    {
        int size = 32;
        byte[] data = randomBytes(size);
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + PATH);

        CountDownLatch handlerComplete = new CountDownLatch(1);
        BasicClientBinaryStreamer client = new BasicClientBinaryStreamer((session, inputStream) ->
        {
            byte[] recv = new byte[16];
            int read = inputStream.read(recv);
            assertThat(read, not(is(0)));
            handlerComplete.countDown();
        });

        Session session = wsClient.connectToServer(client, uri);
        session.getBasicRemote().sendBinary(BufferUtil.toBuffer(data));
        assertTrue(handlerComplete.await(5, TimeUnit.SECONDS));

        session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "close from test"));
        assertTrue(client.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(client.closeReason.getCloseCode(), is(CloseReason.CloseCodes.NORMAL_CLOSURE));
        assertThat(client.closeReason.getReasonPhrase(), is("close from test"));
    }

    @Test
    public void testClosingBeforeReadingToEndOfStream() throws Exception
    {
        int size = 32;
        byte[] data = randomBytes(size);
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + PATH);

        CountDownLatch handlerComplete = new CountDownLatch(1);
        BasicClientBinaryStreamer client = new BasicClientBinaryStreamer((session, inputStream) ->
        {
            byte[] recv = new byte[16];
            int read = inputStream.read(recv);
            assertThat(read, not(is(0)));

            inputStream.close();
            read = inputStream.read(recv);
            assertThat(read, is(-1));
            handlerComplete.countDown();
        });

        Session session = wsClient.connectToServer(client, uri);
        session.getBasicRemote().sendBinary(BufferUtil.toBuffer(data));
        assertTrue(handlerComplete.await(5, TimeUnit.SECONDS));

        session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "close from test"));
        assertTrue(client.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(client.closeReason.getCloseCode(), is(CloseReason.CloseCodes.NORMAL_CLOSURE));
        assertThat(client.closeReason.getReasonPhrase(), is("close from test"));
    }

    private byte[] randomBytes(int size)
    {
        byte[] data = new byte[size];
        new Random().nextBytes(data);
        return data;
    }

    @ClientEndpoint
    public static class BasicClientBinaryStreamer
    {
        public interface MessageHandler
        {
            void accept(Session session, InputStream inputStream) throws Exception;
        }

        private final MessageHandler handler;
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private CloseReason closeReason;

        public BasicClientBinaryStreamer(MessageHandler consumer)
        {
            this.handler = consumer;
        }

        @OnMessage
        public void echoed(Session session, InputStream input) throws Exception
        {
            handler.accept(session, input);
        }

        @OnClose
        public void onClosed(CloseReason closeReason)
        {
            this.closeReason = closeReason;
            closeLatch.countDown();
        }
    }

    @ClientEndpoint
    public static class ClientBinaryStreamer
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        @OnMessage
        public void echoed(InputStream input) throws IOException
        {
            while (true)
            {
                int read = input.read();
                if (read < 0)
                    break;
                output.write(read);
            }
            latch.countDown();
        }

        public byte[] getEcho()
        {
            return output.toByteArray();
        }

        public boolean await(long timeout, TimeUnit unit) throws InterruptedException
        {
            return latch.await(timeout, unit);
        }
    }

    @ServerEndpoint(PATH)
    public static class ServerBinaryStreamer
    {
        @OnMessage
        public void echo(Session session, InputStream input) throws IOException
        {
            byte[] buffer = new byte[128];
            try (OutputStream output = session.getBasicRemote().getSendStream())
            {
                int read;
                while ((read = input.read(buffer)) >= 0)
                {
                    output.write(buffer, 0, read);
                }
            }
        }
    }
}
