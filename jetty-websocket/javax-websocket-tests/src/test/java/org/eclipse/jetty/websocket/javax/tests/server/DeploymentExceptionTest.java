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

package org.eclipse.jetty.websocket.javax.tests.server;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.javax.server.JavaxWebSocketServerContainer;
import org.eclipse.jetty.websocket.javax.tests.server.sockets.InvalidCloseIntSocket;
import org.eclipse.jetty.websocket.javax.tests.server.sockets.InvalidErrorErrorSocket;
import org.eclipse.jetty.websocket.javax.tests.server.sockets.InvalidErrorIntSocket;
import org.eclipse.jetty.websocket.javax.tests.server.sockets.InvalidOpenCloseReasonSocket;
import org.eclipse.jetty.websocket.javax.tests.server.sockets.InvalidOpenIntSocket;
import org.eclipse.jetty.websocket.javax.tests.server.sockets.InvalidOpenSessionIntSocket;
import org.eclipse.jetty.websocket.javax.common.util.InvalidSignatureException;
import org.eclipse.jetty.websocket.servlet.WebSocketMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Deploy various {@link ServerEndpoint} annotated classes with invalid signatures,
 * check for {@link DeploymentException}
 */
public class DeploymentExceptionTest
{
    public static Stream<Arguments> data()
    {
        List<Arguments> data = new ArrayList<>();

        data.add(Arguments.of(InvalidCloseIntSocket.class));
        data.add(Arguments.of(InvalidErrorErrorSocket.class));
        // TODO: data.add(Arguments.of(InvalidErrorExceptionSocket.class));
        data.add(Arguments.of(InvalidErrorIntSocket.class));
        data.add(Arguments.of(InvalidOpenCloseReasonSocket.class));
        data.add(Arguments.of(InvalidOpenIntSocket.class));
        data.add(Arguments.of(InvalidOpenSessionIntSocket.class));

        // TODO: invalid return types
        // TODO: static methods
        // TODO: private or protected methods
        // TODO: abstract methods

        return data.stream();
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testDeploy_InvalidSignature(Class<?> pojo) throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();

        WebSocketMapping factory = new WebSocketMapping();
        HttpClient httpClient = new HttpClient();

        JavaxWebSocketServerContainer container = new JavaxWebSocketServerContainer(factory, httpClient, server.getThreadPool());
        context.addBean(container);

        contexts.addHandler(context);
        try
        {
            context.start();
            Exception e = assertThrows(DeploymentException.class, () -> container.addEndpoint(pojo));
            assertThat(e.getCause(), instanceOf(InvalidSignatureException.class));
        }
        finally
        {
            context.stop();
        }
    }
}
