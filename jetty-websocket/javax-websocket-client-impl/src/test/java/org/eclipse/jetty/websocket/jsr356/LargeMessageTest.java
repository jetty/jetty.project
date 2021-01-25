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

package org.eclipse.jetty.websocket.jsr356;

import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.websocket.ContainerProvider;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class LargeMessageTest
{
    private static final int LARGER_THAN_DEFAULT_SIZE;
    private Server server;

    static
    {
        WebSocketPolicy defaultPolicy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        LARGER_THAN_DEFAULT_SIZE = defaultPolicy.getMaxTextMessageSize() * 3;
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        // This handler is expected to handle echoing of 2MB messages (max)
        EchoHandler echoHandler = new EchoHandler();

        ContextHandler context = new ContextHandler();
        context.setContextPath("/");
        context.setHandler(echoHandler);
        server.setHandler(context);

        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @SuppressWarnings("Duplicates")
    @Test
    public void testLargeEchoAsEndpointInstance() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server

        container.setDefaultMaxTextMessageBufferSize(LARGER_THAN_DEFAULT_SIZE);

        EndpointEchoClient echoer = new EndpointEchoClient();
        assertThat(echoer, instanceOf(javax.websocket.Endpoint.class));

        URI wsUri = WSURI.toWebsocket(server.getURI()).resolve("/");

        // Issue connect using instance of class that extends Endpoint
        Session session = container.connectToServer(echoer, wsUri);
        byte[] buf = new byte[LARGER_THAN_DEFAULT_SIZE];
        Arrays.fill(buf, (byte)'x');
        String message = new String(buf, UTF_8);
        session.getBasicRemote().sendText(message);

        String echoed = echoer.textCapture.messages.poll(1, TimeUnit.SECONDS);
        assertThat("Echoed", echoed, is(message));
    }
}
