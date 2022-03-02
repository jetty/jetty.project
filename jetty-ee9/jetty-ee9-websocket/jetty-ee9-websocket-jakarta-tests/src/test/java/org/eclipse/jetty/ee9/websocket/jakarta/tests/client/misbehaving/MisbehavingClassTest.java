//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.tests.client.misbehaving;

import java.util.concurrent.TimeUnit;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.CoreServer;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
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
            container.connectToServer(socket, null, server.getWsUri());
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
