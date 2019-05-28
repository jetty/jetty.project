//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.tests.client.misbehaving;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.core.CloseException;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.eclipse.jetty.websocket.javax.tests.CoreServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MisbehavingClassTest
{

    private static CoreServer server;

    @SuppressWarnings("Duplicates")
    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new CoreServer(new CoreServer.EchoNegotiator());
        // Start Server
        server.start();
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

        try (StacklessLogging ignored = new StacklessLogging(WebSocketCoreSession.class))
        {
            // expecting IOException during onOpen
            Exception e = assertThrows(IOException.class, () -> container.connectToServer(socket, server.getWsUri()), "Should have failed .connectToServer()");
            assertThat(e.getCause(), instanceOf(CloseException.class));

            assertThat("Close should have occurred", socket.closeLatch.await(1, TimeUnit.SECONDS), is(true));

            Throwable cause = socket.errors.pop();
            assertThat("Error", cause, instanceOf(RuntimeException.class));
        }
    }

    @SuppressWarnings("Duplicates")
    @Test
    public void testAnnotatedRuntimeOnOpen() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        AnnotatedRuntimeOnOpen socket = new AnnotatedRuntimeOnOpen();

        try (StacklessLogging ignored = new StacklessLogging(WebSocketCoreSession.class))
        {
            // expecting IOException during onOpen
            Exception e = assertThrows(IOException.class, () -> container.connectToServer(socket, server.getWsUri()), "Should have failed .connectToServer()");
            assertThat(e.getCause(), instanceOf(CloseException.class));

            assertThat("Close should have occurred", socket.closeLatch.await(5, TimeUnit.SECONDS), is(true));

            Throwable cause = socket.errors.pop();
            assertThat("Error", cause, instanceOf(RuntimeException.class));
        }
    }
}
