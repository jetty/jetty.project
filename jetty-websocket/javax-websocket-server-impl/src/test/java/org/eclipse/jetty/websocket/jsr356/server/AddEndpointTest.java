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

package org.eclipse.jetty.websocket.jsr356.server;

import java.util.concurrent.TimeUnit;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicOpenCloseSocket;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
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
        WebSocketServerContainerInitializer.configure(contextHandler, (context, container) -> containerConsumer.accept(container));
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
        assertThat(deploymentException.getMessage(), containsString("Cannot access default constructor"));
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

        BasicOpenCloseSocket clientEndpoint = new BasicOpenCloseSocket();
        Session session = client.connectToServer(clientEndpoint, WSURI.toWebsocket(server.getURI().resolve("/")));
        assertNotNull(session);
        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), Matchers.is(CloseReason.CloseCodes.NORMAL_CLOSURE));
    }

    @Test
    public void testCustomPrivateEndpoint() throws Exception
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

        BasicOpenCloseSocket clientEndpoint = new BasicOpenCloseSocket();
        Session session = client.connectToServer(clientEndpoint, WSURI.toWebsocket(server.getURI().resolve("/")));
        assertNotNull(session);
        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), Matchers.is(CloseReason.CloseCodes.NORMAL_CLOSURE));
    }

    @Test
    public void testCustomAnnotatedEndpoint() throws Exception
    {
        start(container -> container.addEndpoint(CustomAnnotatedEndpoint.class));

        BasicOpenCloseSocket clientEndpoint = new BasicOpenCloseSocket();
        Session session = client.connectToServer(clientEndpoint, WSURI.toWebsocket(server.getURI().resolve("/")));
        assertNotNull(session);
        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), Matchers.is(CloseReason.CloseCodes.NORMAL_CLOSURE));
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
        assertThat(deploymentException.getMessage(), containsString("Cannot access default constructor"));
    }

    @Test
    public void testAnnotatedMethod()
    {
        RuntimeException error = assertThrows(RuntimeException.class, () ->
            start(container -> container.addEndpoint(AnnotatedServerMethod.class)));

        assertThat(error.getCause(), instanceOf(DeploymentException.class));
        DeploymentException deploymentException = (DeploymentException)error.getCause();
        assertThat(deploymentException.getMessage(), containsString("Method modifier must be public"));
    }
}