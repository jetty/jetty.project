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

package org.eclipse.jetty.websocket.jsr356.misbehaving;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

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
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class MisbehavingClassTest
{
    @Rule
    public ExpectedException expectedException = ExpectedException.none();
    
    private static Server server;
    private static EchoHandler handler;
    private static URI serverUri;

    @SuppressWarnings("Duplicates")
    @BeforeClass
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
        serverUri = new URI(String.format("ws://%s:%d/",host,port));
    }

    @AfterClass
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
        EndpointRuntimeOnOpen socket = new EndpointRuntimeOnOpen();

        try (StacklessLogging logging = new StacklessLogging(EndpointRuntimeOnOpen.class, WebSocketSession.class))
        {
            // expecting IOException during onOpen
            expectedException.expect(IOException.class);
            expectedException.expectCause(instanceOf(RuntimeException.class));
            container.connectToServer(socket, serverUri);
            expectedException.reportMissingExceptionWithMessage("Should have failed .connectToServer()");
            
            assertThat("Close should have occurred",socket.closeLatch.await(1,TimeUnit.SECONDS),is(true));

            Throwable cause = socket.errors.pop();
            assertThat("Error",cause,instanceOf(RuntimeException.class));
        }
    }
    
    @SuppressWarnings("Duplicates")
    @Test
    public void testAnnotatedRuntimeOnOpen() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        AnnotatedRuntimeOnOpen socket = new AnnotatedRuntimeOnOpen();

        try (StacklessLogging logging = new StacklessLogging(AnnotatedRuntimeOnOpen.class, WebSocketSession.class))
        {
            // expecting IOException during onOpen
            expectedException.expect(IOException.class);
            expectedException.expectCause(instanceOf(RuntimeException.class));
            container.connectToServer(socket, serverUri);
            expectedException.reportMissingExceptionWithMessage("Should have failed .connectToServer()");
            
            assertThat("Close should have occurred",socket.closeLatch.await(1,TimeUnit.SECONDS),is(true));

            Throwable cause = socket.errors.pop();
            assertThat("Error",cause,instanceOf(ArrayIndexOutOfBoundsException.class));
        }
    }
}
