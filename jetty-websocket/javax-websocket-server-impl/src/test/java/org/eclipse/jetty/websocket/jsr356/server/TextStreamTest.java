//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.io.Writer;
import java.net.URI;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.ContainerProvider;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TextStreamTest
{
    private static final String PATH = "/echo";
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private Server server;
    private ServerConnector connector;
    private WebSocketContainer wsClient;

    @Before
    public void prepare() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/", true, false);
        ServerContainer container = WebSocketServerContainerInitializer.configureContext(context);
        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(ServerTextStreamer.class, PATH).build();
        container.addEndpoint(config);

        server.start();

        wsClient = ContainerProvider.getWebSocketContainer();
        server.addBean(wsClient, true);
    }

    @After
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

        Assert.assertTrue(client.await(5, TimeUnit.SECONDS));
        Assert.assertArrayEquals(data, client.getEcho());
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
                output.write(data[i]);
        }

        Assert.assertTrue(client.await(5, TimeUnit.SECONDS));
        Assert.assertArrayEquals(data, client.getEcho());
    }

    private char[] randomChars(int size)
    {
        char[] data = new char[size];
        Random random = new Random();
        for (int i = 0; i < data.length; ++i)
            data[i] = CHARS.charAt(random.nextInt(CHARS.length()));
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
                    output.write(buffer, 0, read);
            }
        }
    }
}
