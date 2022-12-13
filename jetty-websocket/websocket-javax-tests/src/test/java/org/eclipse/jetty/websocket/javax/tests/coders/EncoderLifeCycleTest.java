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

package org.eclipse.jetty.websocket.javax.tests.coders;

import java.net.URI;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.Encoder;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.javax.common.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.javax.tests.EchoSocket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EncoderLifeCycleTest
{
    private static final Logger LOG = LoggerFactory.getLogger(EncoderLifeCycleTest.class);
    private static Server server;
    private static URI serverUri;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);

        JavaxWebSocketServletContainerInitializer.configure(contextHandler, ((servletContext, serverContainer) ->
            serverContainer.addEndpoint(ServerEndpointConfig.Builder.create(EchoSocket.class, "/").build())));

        // Start Server
        server.start();
        serverUri = new URI(String.format("ws://localhost:%d/", connector.getLocalPort()));
    }

    public static class StringHolder
    {
        private final String string;

        public StringHolder(String msg)
        {
            string = msg;
        }

        public String getString()
        {
            return string;
        }
    }

    public static class StringHolderSubtype extends StringHolder
    {
        public StringHolderSubtype(String msg)
        {
            super(msg + "|subtype");
        }
    }

    public static class MyEncoder implements Encoder.Text<StringHolder>
    {
        public CountDownLatch initialized = new CountDownLatch(1);
        public CountDownLatch destroyed = new CountDownLatch(1);

        @Override
        public void init(EndpointConfig config)
        {
            initialized.countDown();
        }

        @Override
        public String encode(StringHolder message)
        {
            return message.getString();
        }

        @Override
        public void destroy()
        {
            destroyed.countDown();
        }
    }

    public static class TextMessageEndpoint extends Endpoint implements MessageHandler.Whole<String>
    {
        public BlockingArrayQueue<String> textMessages = new BlockingArrayQueue<>();
        public CountDownLatch openLatch = new CountDownLatch(1);
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public CloseReason closeReason = null;

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(this);
            this.openLatch.countDown();
        }

        @Override
        public void onClose(Session session, CloseReason closeReason)
        {
            this.closeReason = closeReason;
            this.closeLatch.countDown();
        }

        @Override
        public void onMessage(String message)
        {
            this.textMessages.add(message);
        }
    }

    @ParameterizedTest
    @ValueSource(classes = {StringHolder.class, StringHolderSubtype.class})
    public void testEncoderLifeCycle(Class<? extends StringHolder> clazz) throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        TextMessageEndpoint clientEndpoint = new TextMessageEndpoint();
        ClientEndpointConfig clientConfig = ClientEndpointConfig.Builder.create()
            .encoders(Collections.singletonList(MyEncoder.class))
            .build();

        // Send an instance of our StringHolder type.
        Session session = container.connectToServer(clientEndpoint, clientConfig, serverUri);
        StringHolder data = clazz.getConstructor(String.class).newInstance("test1");
        session.getBasicRemote().sendObject(data);

        // We received the expected echo.
        String echoed = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat("Echoed message", echoed, is(data.getString()));

        // Verify that the encoder has been opened.
        AvailableEncoders encoderFactory = ((JavaxWebSocketSession)session).getEncoders();
        Object obj = encoderFactory.getInstanceFor(data.getClass());
        assertThat(obj.getClass(), is(MyEncoder.class));
        MyEncoder encoder = (MyEncoder)obj;
        assertThat(encoder.initialized.getCount(), is(0L));

        // Verify the Encoder has not been destroyed, but is destroyed after the session is closed.
        assertThat(encoder.destroyed.getCount(), is(1L));
        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(encoder.destroyed.await(5, TimeUnit.SECONDS));
    }
}
