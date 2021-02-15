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

package org.eclipse.jetty.websocket.jsr356.misbehaving;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.jsr356.EchoHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MisbehavingClassTest
{
    private static Server server;
    private static EchoHandler handler;
    private static URI serverUri;

    @SuppressWarnings("Duplicates")
    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        handler = new EchoHandler();

        ContextHandler context = new ContextHandler();
        context.setContextPath("/");
        context.setHandler(handler);
        server.setHandler(context);

        // Start Server
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
    public static void stopServer()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }
    }

    @SuppressWarnings("Duplicates")
    @Test
    public void testEndpointRuntimeOnOpen() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        EndpointRuntimeOnOpen socket = new EndpointRuntimeOnOpen();

        try (StacklessLogging ignore = new StacklessLogging(EndpointRuntimeOnOpen.class, WebSocketSession.class))
        {
            // expecting IOException during onOpen - Should have failed .connectToServer()
            IOException e = assertThrows(IOException.class,
                () -> container.connectToServer(socket, serverUri));
            assertThat(e.getCause(), instanceOf(RuntimeException.class));

            assertThat("Close should have occurred", socket.closeLatch.await(10, TimeUnit.SECONDS), is(true));
            assertThat("Error", socket.errors.pop(), instanceOf(RuntimeException.class));
        }
    }

    @Test
    public void testAnnotatedRuntimeOnOpen() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        AnnotatedRuntimeOnOpen socket = new AnnotatedRuntimeOnOpen();

        try (StacklessLogging ignore = new StacklessLogging(AnnotatedRuntimeOnOpen.class, WebSocketSession.class))
        {
            // expecting IOException during onOpen - Should have failed .connectToServer()
            IOException e = assertThrows(IOException.class,
                () -> container.connectToServer(socket, serverUri));
            assertThat(e.getCause(), instanceOf(RuntimeException.class));

            assertThat("Close should have occurred", socket.closeLatch.await(10, TimeUnit.SECONDS), is(true));
            assertThat("Error", socket.errors.pop(), instanceOf(RuntimeException.class));
        }
    }
}
