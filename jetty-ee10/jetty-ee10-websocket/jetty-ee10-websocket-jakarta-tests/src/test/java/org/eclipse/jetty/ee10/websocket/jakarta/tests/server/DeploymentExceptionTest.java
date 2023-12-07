//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.jakarta.tests.server;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.server.sockets.BadEndpoint;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.server.sockets.InvalidCloseIntSocket;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.server.sockets.InvalidErrorErrorSocket;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.server.sockets.InvalidErrorIntSocket;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.server.sockets.InvalidOpenCloseReasonSocket;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.server.sockets.InvalidOpenIntSocket;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.server.sockets.InvalidOpenSessionIntSocket;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private Handler.Sequence contexts;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server(0);
        contexts = new Handler.Sequence();
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
    public void testDeployInvalidSignature(Class<?> pojo) throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.setServer(server);
        JakartaWebSocketServletContainerInitializer.configure(context, null);

        contexts.addHandler(context);
        try
        {
            context.start();
            ServerContainer serverContainer = (ServerContainer)context.getServletContext().getAttribute(ServerContainer.class.getName());
            Exception e = assertThrows(DeploymentException.class, () -> serverContainer.addEndpoint(pojo));
            assertThat(e.getCause(), instanceOf(InvalidSignatureException.class));
        }
        finally
        {
            context.stop();
        }
    }

    @Test
    public void testDeploymentException() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.setServer(server);
        JakartaWebSocketServletContainerInitializer.configure(context, null);

        contexts.addHandler(context);
        try
        {
            context.start();
            ServerContainer serverContainer = (ServerContainer)context.getServletContext().getAttribute(ServerContainer.class.getName());

            // We cannot deploy this because it does not extend Endpoint and has no @ServerEndpoint/@ClientEndpoint annotation.
            assertThrows(DeploymentException.class, () ->
                serverContainer.addEndpoint(BadEndpoint.class));
            assertThrows(DeploymentException.class, () ->
                serverContainer.addEndpoint(ServerEndpointConfig.Builder.create(BadEndpoint.class, "/ws").build()));
        }
        finally
        {
            context.stop();
        }
    }
}
