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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

public class BinaryStreamTest
{
    private static final String PATH = "/echo";

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
        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(ServerBinaryStreamer.class, PATH).build();
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
        byte[] data = randomBytes(size);
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + PATH);
        ClientBinaryStreamer client = new ClientBinaryStreamer();
        Session session = wsClient.connectToServer(client, uri);

        try (OutputStream output = session.getBasicRemote().getSendStream())
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
        byte[] data = randomBytes(size);
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + PATH);
        ClientBinaryStreamer client = new ClientBinaryStreamer();
        Session session = wsClient.connectToServer(client, uri);

        try (OutputStream output = session.getBasicRemote().getSendStream())
        {
            for (int i = 0; i < size; ++i)
                output.write(data[i]);
        }

        Assert.assertTrue(client.await(5, TimeUnit.SECONDS));
        Assert.assertArrayEquals(data, client.getEcho());
    }

    private byte[] randomBytes(int size)
    {
        byte[] data = new byte[size];
        new Random().nextBytes(data);
        return data;
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
                    output.write(buffer, 0, read);
            }
        }
    }
}
