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

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpoint;
import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TextStreamTest
{
    private static final String PATH = "/echo";
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final BlockingArrayQueue<QueuedTextStreamer> serverEndpoints = new BlockingArrayQueue<>();

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
        ServerContainer container = WebSocketServerContainerInitializer.configureContext(context);
        container.addEndpoint(ServerEndpointConfig.Builder.create(ServerTextStreamer.class, PATH).build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(QueuedTextStreamer.class, "/test").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(QueuedPartialTextStreamer.class, "/partial").build());

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
        char[] data = randomChars(size);
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + PATH);
        ClientTextStreamer client = new ClientTextStreamer();
        Session session = wsClient.connectToServer(client, uri);

        try (Writer output = session.getBasicRemote().getSendWriter())
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
        char[] data = randomChars(size);
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + PATH);
        ClientTextStreamer client = new ClientTextStreamer();
        Session session = wsClient.connectToServer(client, uri);

        try (Writer output = session.getBasicRemote().getSendWriter())
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
    public void testMessageOrdering() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/test");
        ClientTextStreamer client = new ClientTextStreamer();
        Session session = wsClient.connectToServer(client, uri);

        final int numLoops = 20;
        for (int i = 0; i < numLoops; i++)
            session.getBasicRemote().sendText(Integer.toString(i));
        session.close();

        QueuedTextStreamer queuedTextStreamer = serverEndpoints.poll(5, TimeUnit.SECONDS);
        assertNotNull(queuedTextStreamer);
        for (int i = 0; i < numLoops; i++)
        {
            String msg = queuedTextStreamer.messages.poll(5, TimeUnit.SECONDS);
            assertThat(msg, Matchers.is(Integer.toString(i)));
        }
    }

    @Test
    public void testFragmentedMessageOrdering() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/test");
        ClientTextStreamer client = new ClientTextStreamer();
        Session session = wsClient.connectToServer(client, uri);

        final int numLoops = 20;
        for (int i = 0; i < numLoops; i++)
        {
            session.getBasicRemote().sendText("firstFrame" + i, false);
            session.getBasicRemote().sendText("|secondFrame"  + i, false);
            session.getBasicRemote().sendText("|finalFrame" + i, true);
        }
        session.close();

        QueuedTextStreamer queuedTextStreamer = serverEndpoints.poll(5, TimeUnit.SECONDS);
        assertNotNull(queuedTextStreamer);
        for (int i = 0; i < numLoops; i++)
        {
            String msg = queuedTextStreamer.messages.poll(5, TimeUnit.SECONDS);
            String expected = "firstFrame" + i + "|secondFrame"  + i + "|finalFrame" + i;
            assertThat(msg, Matchers.is(expected));
        }
    }

    @Test
    public void testMessageOrderingDoNotReadToEOF() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/partial");
        ClientTextStreamer client = new ClientTextStreamer();
        Session session = wsClient.connectToServer(client, uri);

        final int numLoops = 20;
        for (int i = 0; i < numLoops; i++)
        {
            session.getBasicRemote().sendText(i + "|-----");
        }
        session.close();

        QueuedTextStreamer queuedTextStreamer = serverEndpoints.poll(5, TimeUnit.SECONDS);
        assertNotNull(queuedTextStreamer);
        for (int i = 0; i < numLoops; i++)
        {
            String msg = queuedTextStreamer.messages.poll(5, TimeUnit.SECONDS);
            assertThat(msg, Matchers.is(Integer.toString(i)));
        }
    }

    private char[] randomChars(int size)
    {
        char[] data = new char[size];
        Random random = new Random();
        for (int i = 0; i < data.length; ++i)
        {
            data[i] = CHARS.charAt(random.nextInt(CHARS.length()));
        }
        return data;
    }

    @ClientEndpoint
    public static class ClientTextStreamer
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final StringBuilder output = new StringBuilder();

        @OnMessage
        public void echoed(Reader input) throws IOException
        {
            while (true)
            {
                int read = input.read();
                if (read < 0)
                    break;
                output.append((char)read);
            }
            latch.countDown();
        }

        public char[] getEcho()
        {
            return output.toString().toCharArray();
        }

        public boolean await(long timeout, TimeUnit unit) throws InterruptedException
        {
            return latch.await(timeout, unit);
        }
    }

    @ServerEndpoint(PATH)
    public static class ServerTextStreamer
    {
        @OnMessage
        public void echo(Session session, Reader input) throws IOException
        {
            char[] buffer = new char[128];
            try (Writer output = session.getBasicRemote().getSendWriter())
            {
                int read;
                while ((read = input.read(buffer)) >= 0)
                {
                    output.write(buffer, 0, read);
                }
            }
        }
    }

    public static class QueuedTextStreamer extends Endpoint implements MessageHandler.Whole<Reader>
    {
        protected BlockingArrayQueue<String> messages = new BlockingArrayQueue<>();

        public QueuedTextStreamer()
        {
            serverEndpoints.add(this);
        }

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(this);
        }

        @Override
        public void onMessage(Reader input)
        {
            try
            {
                Thread.sleep(Math.abs(new Random().nextLong() % 200));
                messages.add(IO.toString(input));
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    public static class QueuedPartialTextStreamer extends QueuedTextStreamer
    {
        @Override
        public void onMessage(Reader input)
        {
            try
            {
                Thread.sleep(Math.abs(new Random().nextLong() % 200));

                // Do not read to EOF but just the first '|'.
                StringWriter writer = new StringWriter();
                while (true)
                {
                    int read = input.read();
                    if (read < 0 || read == '|')
                        break;
                    writer.write(read);
                }

                messages.add(writer.toString());
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }
}
