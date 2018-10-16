//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.server.jsr356;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.stream.Stream;

import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.server.NativeWebSocketConfiguration;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.tests.jsr356.sockets.InvalidCloseIntSocket;
import org.eclipse.jetty.websocket.tests.jsr356.sockets.InvalidErrorErrorSocket;
import org.eclipse.jetty.websocket.tests.jsr356.sockets.InvalidErrorExceptionSocket;
import org.eclipse.jetty.websocket.tests.jsr356.sockets.InvalidErrorIntSocket;
import org.eclipse.jetty.websocket.tests.jsr356.sockets.InvalidOpenCloseReasonSocket;
import org.eclipse.jetty.websocket.tests.jsr356.sockets.InvalidOpenIntSocket;
import org.eclipse.jetty.websocket.tests.jsr356.sockets.InvalidOpenSessionIntSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Deploy various {@link ServerEndpoint} annotated classes with invalid signatures,
 * check for {@link DeploymentException}
 */
public class DeploymentExceptionTest
{
    public static Stream<Arguments> data()
    {
        Class<?> invalidSockets[] = new Class[] {
                InvalidCloseIntSocket.class,
                InvalidErrorErrorSocket.class,
                InvalidErrorExceptionSocket.class,
                InvalidErrorIntSocket.class,
                InvalidOpenCloseReasonSocket.class,
                InvalidOpenIntSocket.class,
                InvalidOpenSessionIntSocket.class
        };

        // TODO: invalid return types
        // TODO: static methods
        // TODO: private or protected methods
        // TODO: abstract methods
        
        return Arrays.stream(invalidSockets).map(Arguments::of);
    }
    
    private Server server;
    private HandlerCollection contexts;
    
    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server(0);
        contexts = new HandlerCollection(true, new Handler[0]);
        server.setHandler(contexts);
        server.start();
    }
    
    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }
    
    @ParameterizedTest
    @MethodSource("data")
    public void testDeploy_InvalidSignature(Class<?> pojo) throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        
        WebSocketServerFactory serverFactory = new WebSocketServerFactory(context.getServletContext());
        NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration(serverFactory);
        
        ServerContainer container = new ServerContainer(configuration, server.getThreadPool());
        context.addBean(container);
    
        contexts.addHandler(context);
        try
        {
            context.start();
            assertThrows(DeploymentException.class, () -> container.addEndpoint(pojo));
        }
        finally
        {
            context.stop();
        }
    }
}
