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

package org.eclipse.jetty.cdi.tests.websocket;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.inject.Inject;
import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.HandshakeResponse;
import javax.websocket.MessageHandler;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.cdi.CdiDecoratingListener;
import org.eclipse.jetty.cdi.CdiServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.eclipse.jetty.websocket.javax.client.JavaxWebSocketClientContainerProvider;
import org.eclipse.jetty.websocket.javax.client.internal.JavaxWebSocketClientContainer;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaxWebSocketCdiTest
{
    private Server _server;
    private WebSocketContainer _client;
    private ServerConnector _connector;
    private ServletContextHandler context;

    @BeforeEach
    public void before()
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        context = new ServletContextHandler();
        context.setContextPath("/");

        // Enable Weld + CDI
        context.setInitParameter(CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, CdiDecoratingListener.MODE);
        context.addServletContainerInitializer(new CdiServletContainerInitializer());
        context.addServletContainerInitializer(new org.jboss.weld.environment.servlet.EnhancedListener());

        // Add to Server
        _server.setHandler(context);
    }

    public void start(Configurator configurator) throws Exception
    {
        // Add WebSocket endpoints
        JavaxWebSocketServletContainerInitializer.configure(context, configurator);

        // Start Server
        _server.start();

        // Configure the Client with the same DecoratedObjectFactory from the server.
        WebSocketComponents components = WebSocketServerComponents.getWebSocketComponents(context.getServletContext());
        _client = new JavaxWebSocketClientContainer(components);
        LifeCycle.start(_client);
    }

    @AfterEach
    public void after() throws Exception
    {
        JavaxWebSocketClientContainerProvider.stop(_client);
        _server.stop();
    }

    @Test
    public void testAnnotatedEndpoint() throws Exception
    {
        start((servletContext, wsContainer) -> wsContainer.addEndpoint(AnnotatedCdiEchoSocket.class));

        // If we can get an echo from the websocket endpoint we know that CDI injection of the logger worked as there was no NPE.
        AnnotatedCdiClientSocket clientEndpoint = new AnnotatedCdiClientSocket();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/echo");
        Session session = _client.connectToServer(clientEndpoint, uri);
        session.getBasicRemote().sendText("hello world");
        assertThat(clientEndpoint._textMessages.poll(5, TimeUnit.SECONDS), is("hello world"));
        session.close();
        assertTrue(clientEndpoint._closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testConfiguredEndpoint() throws Exception
    {
        ServerEndpointConfig serverEndpointConfig = ServerEndpointConfig.Builder.create(ConfiguredCdiEchoSocket.class, "/echo")
            .configurator(new ServerConfigurator())
            .build();
        start((servletContext, wsContainer) -> wsContainer.addEndpoint(serverEndpointConfig));

        // If we can get an echo from the websocket endpoint we know that CDI injection of the logger worked as there was no NPE.
        ConfiguredCdiClientSocket clientEndpoint = new ConfiguredCdiClientSocket();
        ClientEndpointConfig clientEndpointConfig = ClientEndpointConfig.Builder.create()
            .configurator(new ClientConfigurator())
            .build();

        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/echo");
        Session session = _client.connectToServer(clientEndpoint, clientEndpointConfig, uri);
        session.getBasicRemote().sendText("hello world");
        assertThat(clientEndpoint._textMessages.poll(5, TimeUnit.SECONDS), is("hello world"));
        session.close();
        assertTrue(clientEndpoint._closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testEncoderDecoder() throws Exception
    {
        start((servletContext, wsContainer) -> wsContainer.addEndpoint(AnnotatedCdiEchoSocket.class));

        // If we can get an echo from the websocket endpoint we know that CDI injection of the logger worked as there was no NPE.
        AnnotatedEncoderDecoderClientSocket clientEndpoint = new AnnotatedEncoderDecoderClientSocket();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/echo");
        Session session = _client.connectToServer(clientEndpoint, uri);
        session.getBasicRemote().sendObject("hello world");
        assertThat(clientEndpoint._textMessages.poll(5, TimeUnit.SECONDS), is("decoded(encoded(hello world))"));
        session.close();
        assertTrue(clientEndpoint._closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Disabled("See issue https://github.com/eclipse/jetty.project/issues/6174")
    public void testHttpSessionInjection() throws Exception
    {
        start((servletContext, wsContainer) -> wsContainer.addEndpoint(CdiHttpSessionSocket.class));

        // If we can get an echo from the websocket endpoint we know that CDI injection of the logger worked as there was no NPE.
        AnnotatedCdiClientSocket clientEndpoint = new AnnotatedCdiClientSocket();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/echo");
        Session session = _client.connectToServer(clientEndpoint, uri);
        session.getBasicRemote().sendObject("hello world");
        String rcvMessage = clientEndpoint._textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(rcvMessage, containsString("hello world, SessionID:"));
        session.close();
        assertTrue(clientEndpoint._closeLatch.await(5, TimeUnit.SECONDS));
    }

    public static class ClientConfigurator extends ClientEndpointConfig.Configurator
    {
        @Inject
        public Logger logger;

        @Override
        public void beforeRequest(Map<String, List<String>> headers)
        {
            logger.info("beforeRequest");
        }
    }

    public static class ServerConfigurator extends ServerEndpointConfig.Configurator
    {
        @Inject
        public Logger logger;

        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            logger.info("modifyHandshake");
        }
    }

    @ClientEndpoint(configurator = ClientConfigurator.class)
    public static class AnnotatedCdiClientSocket
    {
        @Inject
        public Logger logger;

        BlockingArrayQueue<String> _textMessages = new BlockingArrayQueue<>();
        CountDownLatch _closeLatch = new CountDownLatch(1);

        @OnOpen
        public void onOpen(Session session)
        {
            logger.info("onOpen: " + session);
        }

        @OnMessage
        public void onMessage(String message)
        {
            _textMessages.add(message);
        }

        @OnError
        public void onError(Throwable t)
        {
            t.printStackTrace();
        }

        @OnClose
        public void onClose()
        {
            _closeLatch.countDown();
        }
    }

    @ServerEndpoint(value = "/echo", configurator = ServerConfigurator.class)
    public static class AnnotatedCdiEchoSocket
    {
        @Inject
        protected Logger logger;
        protected Session session;

        @OnOpen
        public void onOpen(Session session)
        {
            logger.info("onOpen() session:" + session);
            this.session = session;
        }

        @OnMessage
        public void onMessage(String message) throws IOException
        {
            this.session.getBasicRemote().sendText(message);
        }

        @OnError
        public void onError(Throwable t)
        {
            t.printStackTrace();
        }
    }

    public static class ConfiguredCdiClientSocket extends Endpoint implements MessageHandler.Whole<String>
    {
        @Inject
        public Logger logger;

        BlockingArrayQueue<String> _textMessages = new BlockingArrayQueue<>();
        CountDownLatch _closeLatch = new CountDownLatch(1);

        @Override
        public void onMessage(String message)
        {
            _textMessages.add(message);
        }

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            logger.info("onOpen: " + session);
            session.addMessageHandler(this);
        }

        @Override
        public void onError(Session session, Throwable thr)
        {
            thr.printStackTrace();
        }

        @Override
        public void onClose(Session session, CloseReason closeReason)
        {
            _closeLatch.countDown();
        }
    }

    public static class ConfiguredCdiEchoSocket extends Endpoint implements MessageHandler.Whole<String>
    {
        @Inject
        public Logger logger;
        private Session session;

        @Override
        public void onMessage(String message)
        {
            try
            {
                session.getBasicRemote().sendText(message);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            logger.info("onOpen() session:" + session);
            this.session = session;
            session.addMessageHandler(this);
        }

        @Override
        public void onError(Session session, Throwable thr)
        {
            thr.printStackTrace();
        }
    }

    public static class CustomEncoder implements Encoder.Text<String>
    {
        @Inject
        public Logger logger;

        @Override
        public String encode(String s)
        {
            return "encoded(" + s + ")";
        }

        @Override
        public void init(EndpointConfig config)
        {
            logger.info("init: " + config);
        }

        @Override
        public void destroy()
        {
        }
    }

    public static class CustomDecoder implements Decoder.Text<String>
    {
        @Inject
        public Logger logger;

        @Override
        public String decode(String s)
        {
            return "decoded(" + s + ")";
        }

        @Override
        public void init(EndpointConfig config)
        {
            logger.info("init: " + config);
        }

        @Override
        public void destroy()
        {
        }

        @Override
        public boolean willDecode(String s)
        {
            return true;
        }
    }

    @ClientEndpoint(encoders = {CustomEncoder.class}, decoders = {CustomDecoder.class})
    public static class AnnotatedEncoderDecoderClientSocket extends AnnotatedCdiClientSocket
    {
    }

    @ServerEndpoint("/echo")
    public static class CdiHttpSessionSocket extends AnnotatedCdiEchoSocket
    {
        @Inject
        private javax.servlet.http.HttpSession httpSession;

        @Override
        public void onMessage(String message) throws IOException
        {
            session.getBasicRemote().sendText(message + ", SessionID:" + httpSession.getId());
        }
    }
}
