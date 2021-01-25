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

import java.io.File;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.jsr356.server.samples.pong.PongContextListener;
import org.eclipse.jetty.websocket.jsr356.server.samples.pong.PongMessageEndpoint;
import org.eclipse.jetty.websocket.jsr356.server.samples.pong.PongSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class PingPongTest
{
    private static WSServer server;
    private static URI serverUri;
    private static WebSocketContainer client;

    @BeforeAll
    public static void startServer() throws Exception
    {
        File testdir = MavenTestingUtils.getTargetTestingDir(PingPongTest.class.getName());
        server = new WSServer(testdir, "app");
        server.copyWebInf("pong-config-web.xml");

        server.copyClass(PongContextListener.class);
        server.copyClass(PongMessageEndpoint.class);
        server.copyClass(PongSocket.class);

        server.start();
        serverUri = server.getServerBaseURI();

        WebAppContext webapp = server.createWebAppContext();
        server.deployWebapp(webapp);
    }

    @BeforeAll
    public static void startClient() throws Exception
    {
        client = ContainerProvider.getWebSocketContainer();
    }

    @AfterAll
    public static void stopServer()
    {
        server.stop();
    }

    @Test
    public void testPingEndpoint() throws Exception
    {
        EchoClientSocket socket = new EchoClientSocket();
        URI toUri = serverUri.resolve("ping");

        try
        {
            // Connect
            client.connectToServer(socket, toUri);
            socket.waitForConnected(1, TimeUnit.SECONDS);

            // Send Ping
            String msg = "hello";
            socket.sendPing(msg);

            // Validate Responses
            String actual = socket.eventQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Received Ping Response", actual, containsString("PongMessage[/ping]:" + msg));
        }
        finally
        {
            // Close
            socket.close();
        }
    }

    @Test
    public void testPongEndpoint() throws Exception
    {
        EchoClientSocket socket = new EchoClientSocket();
        URI toUri = serverUri.resolve("pong");

        try
        {
            // Connect
            client.connectToServer(socket, toUri);
            socket.waitForConnected(1, TimeUnit.SECONDS);

            // Send Ping
            String msg = "hello";
            socket.sendPong(msg);

            // Validate Responses
            String received = socket.eventQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Received Ping Responses", received, containsString("PongMessage[/pong]:" + msg));
        }
        finally
        {
            // Close
            socket.close();
        }
    }

    @Test
    public void testPingSocket() throws Exception
    {
        EchoClientSocket socket = new EchoClientSocket();
        URI toUri = serverUri.resolve("ping-socket");

        try
        {
            // Connect
            client.connectToServer(socket, toUri);
            socket.waitForConnected(1, TimeUnit.SECONDS);

            // Send Ping
            String msg = "hello";
            socket.sendPing(msg);

            // Validate Responses
            String actual = socket.eventQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Received Ping Response", actual, containsString("@OnMessage(PongMessage)[/ping-socket]:" + msg));
        }
        finally
        {
            // Close
            socket.close();
        }
    }

    @Test
    public void testPongSocket() throws Exception
    {
        EchoClientSocket socket = new EchoClientSocket();
        URI toUri = serverUri.resolve("pong-socket");

        try
        {
            // Connect
            client.connectToServer(socket, toUri);
            socket.waitForConnected(1, TimeUnit.SECONDS);

            // Send Ping
            String msg = "hello";
            socket.sendPong(msg);

            // Validate Responses
            String received = socket.eventQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Received Ping Responses", received, containsString("@OnMessage(PongMessage)[/pong-socket]:" + msg));
        }
        finally
        {
            // Close
            socket.close();
        }
    }
}
