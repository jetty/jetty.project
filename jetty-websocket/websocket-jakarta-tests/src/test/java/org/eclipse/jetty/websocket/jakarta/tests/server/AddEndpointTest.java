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

package org.eclipse.jetty.websocket.jakarta.tests.server;

import java.util.concurrent.TimeUnit;

import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.jakarta.tests.WSURI;
import org.eclipse.jetty.websocket.jakarta.tests.client.samples.CloseSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AddEndpointTest
{
    private Server server;
    private WebSocketContainer client;
    private ServletContextHandler contextHandler;

    @BeforeEach
    public void before()
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);
        client = ContainerProvider.getWebSocketContainer();
    }

    @AfterEach
    public void after() throws Exception
    {
        LifeCycle.stop(client);
        server.stop();
    }

    public interface CheckedConsumer<T>
    {
        void accept(T t) throws DeploymentException;
    }

    public void start(CheckedConsumer<ServerContainer> containerConsumer) throws Exception
    {
        JakartaWebSocketServletContainerInitializer.configure(contextHandler, (context, container) -> containerConsumer.accept(container));
        server.start();
    }

    private static class ServerSocket extends Endpoint implements MessageHandler.Whole<String>
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(this);
        }

        @Override
        public void onMessage(String message)
        {
        }
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    private class CustomPrivateEndpoint extends Endpoint
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
        }
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    @ServerEndpoint(value = "/", configurator = CustomAnnotatedEndpointConfigurator.class)
    public static class CustomAnnotatedEndpoint
    {
        public CustomAnnotatedEndpoint(String id)
        {
        }

        @OnOpen
        public void onOpen(Session session, EndpointConfig config)
        {
        }
    }

    public static class CustomAnnotatedEndpointConfigurator extends ServerEndpointConfig.Configurator
    {
        @SuppressWarnings("unchecked")
        @Override
        public <T> T getEndpointInstance(Class<T> endpointClass)
        {
            return (T)new CustomAnnotatedEndpoint("server");
        }
    }

    public static class CustomEndpoint extends Endpoint implements MessageHandler.Whole<String>
    {
        public CustomEndpoint(String id)
        {
            // This is a valid no-default-constructor implementation, and can be added via a custom
            // ServerEndpointConfig.Configurator
        }

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(this);
        }

        @Override
        public void onMessage(String message)
        {
        }
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class ServerSocketNonStatic extends Endpoint implements MessageHandler.Whole<String>
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(this);
        }

        @Override
        public void onMessage(String message)
        {
        }
    }

    @ServerEndpoint("/annotated")
    private static class AnnotatedServerSocket
    {
        @OnMessage
        public void onMessage(String message)
        {
        }
    }

    @ServerEndpoint("/annotatedMethod")
    public static class AnnotatedServerMethod
    {
        @OnMessage
        private void onMessage(String message)
        {
        }
    }

    @Test
    public void testEndpoint()
    {
        RuntimeException error = assertThrows(RuntimeException.class, () ->
        {
            ServerEndpointConfig config = ServerEndpointConfig.Builder.create(ServerSocket.class, "/").build();
            start(container -> container.addEndpoint(config));
        });

        assertThat(error.getCause(), instanceOf(DeploymentException.class));
        DeploymentException deploymentException = (DeploymentException)error.getCause();
        assertThat(deploymentException.getMessage(), containsString("Class is not public"));
    }

    @Test
    public void testCustomEndpoint() throws Exception
    {
        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(CustomEndpoint.class, "/")
            .configurator(new ServerEndpointConfig.Configurator()
            {
                @SuppressWarnings("unchecked")
                @Override
                public <T> T getEndpointInstance(Class<T> endpointClass)
                {
                    return (T)new CustomEndpoint("server");
                }
            }).build();
        start(container -> container.addEndpoint(config));

        CloseSocket clientEndpoint = new CloseSocket();
        Session session = client.connectToServer(clientEndpoint, WSURI.toWebsocket(server.getURI().resolve("/")));
        assertNotNull(session);
        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        CloseReason closeReason = clientEndpoint.closeDetail.get();
        assertThat(closeReason, anyOf(nullValue(), is(CloseReason.CloseCodes.NORMAL_CLOSURE)));
    }

    @Test
    public void testCustomPrivateEndpoint()
    {
        RuntimeException error = assertThrows(RuntimeException.class, () ->
        {
            ServerEndpointConfig config = ServerEndpointConfig.Builder.create(CustomPrivateEndpoint.class, "/")
                .configurator(new ServerEndpointConfig.Configurator()
                {
                    @SuppressWarnings("unchecked")
                    @Override
                    public <T> T getEndpointInstance(Class<T> endpointClass)
                    {
                        return (T)new CustomPrivateEndpoint();
                    }
                }).build();
            start(container -> container.addEndpoint(config));
        });

        assertThat(error.getCause(), instanceOf(DeploymentException.class));
        DeploymentException deploymentException = (DeploymentException)error.getCause();
        assertThat(deploymentException.getMessage(), containsString("Class is not public"));
    }

    @Test
    public void testCustomAnnotatedEndpoint() throws Exception
    {
        start(container -> container.addEndpoint(CustomAnnotatedEndpoint.class));

        CloseSocket clientEndpoint = new CloseSocket();
        Session session = client.connectToServer(clientEndpoint, WSURI.toWebsocket(server.getURI().resolve("/")));
        assertNotNull(session);
        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        CloseReason closeReason = clientEndpoint.closeDetail.get();
        assertThat(closeReason, anyOf(nullValue(), is(CloseReason.CloseCodes.NORMAL_CLOSURE)));
    }

    @Test
    public void testCustomEndpointNoConfigurator()
    {
        RuntimeException error = assertThrows(RuntimeException.class, () ->
        {
            ServerEndpointConfig config = ServerEndpointConfig.Builder.create(CustomEndpoint.class, "/").build();
            start(container -> container.addEndpoint(config));
        });

        assertThat(error.getCause(), instanceOf(DeploymentException.class));
        DeploymentException deploymentException = (DeploymentException)error.getCause();
        assertThat(deploymentException.getMessage(), containsString("Cannot access default constructor"));
    }

    @Test
    public void testInnerEndpoint()
    {
        RuntimeException error = assertThrows(RuntimeException.class, () ->
            start(container -> container.addEndpoint(ServerEndpointConfig.Builder.create(ServerSocketNonStatic.class, "/").build())));

        assertThat(error.getCause(), instanceOf(DeploymentException.class));
        DeploymentException deploymentException = (DeploymentException)error.getCause();
        assertThat(deploymentException.getMessage(), containsString("Cannot access default constructor"));
    }

    @Test
    public void testAnnotatedEndpoint()
    {
        RuntimeException error = assertThrows(RuntimeException.class, () ->
            start(container -> container.addEndpoint(AnnotatedServerSocket.class)));

        assertThat(error.getCause(), instanceOf(DeploymentException.class));
        DeploymentException deploymentException = (DeploymentException)error.getCause();
        assertThat(deploymentException.getMessage(), containsString("Class is not public"));
    }

    @Test
    public void testAnnotatedMethod()
    {
        RuntimeException error = assertThrows(RuntimeException.class, () ->
            start(container ->
                container.addEndpoint(AnnotatedServerMethod.class)));

        assertThat(error.getCause(), instanceOf(DeploymentException.class));
        DeploymentException deploymentException = (DeploymentException)error.getCause();
        assertThat(deploymentException.getMessage(), containsString("method must be public"));
    }
}
