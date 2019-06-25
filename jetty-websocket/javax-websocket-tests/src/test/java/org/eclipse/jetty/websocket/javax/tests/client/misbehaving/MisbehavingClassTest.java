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

import java.util.concurrent.TimeUnit;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.eclipse.jetty.websocket.javax.tests.CoreServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class MisbehavingClassTest
{
    private CoreServer server;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new CoreServer(new CoreServer.EchoNegotiator());
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testEndpointRuntimeOnOpen() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        EndpointRuntimeOnOpen socket = new EndpointRuntimeOnOpen();

        try (StacklessLogging ignored = new StacklessLogging(WebSocketCoreSession.class))
        {
            // expecting RuntimeException during onOpen
            container.connectToServer(socket, server.getWsUri());
            assertThat("Close should have occurred", socket.closeLatch.await(1, TimeUnit.SECONDS), is(true));
            Throwable cause = socket.errors.pop();
            assertThat("Error", cause, instanceOf(RuntimeException.class));
        }
    }

    @Test
    public void testAnnotatedRuntimeOnOpen() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        AnnotatedRuntimeOnOpen socket = new AnnotatedRuntimeOnOpen();

        try (StacklessLogging ignored = new StacklessLogging(WebSocketCoreSession.class))
        {
            // expecting RuntimeException during onOpen
            container.connectToServer(socket, server.getWsUri());
            assertThat("Close should have occurred", socket.closeLatch.await(5, TimeUnit.SECONDS), is(true));
            Throwable cause = socket.errors.pop();
            assertThat("Error", cause, instanceOf(RuntimeException.class));
        }
    }
}
