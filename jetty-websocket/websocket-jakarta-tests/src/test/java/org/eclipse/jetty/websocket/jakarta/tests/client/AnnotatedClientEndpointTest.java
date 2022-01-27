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

package org.eclipse.jetty.websocket.jakarta.tests.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Date;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.jetty.websocket.jakarta.tests.CoreServer;
import org.eclipse.jetty.websocket.jakarta.tests.coders.DateDecoder;
import org.eclipse.jetty.websocket.jakarta.tests.coders.TimeEncoder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static java.util.stream.Collectors.joining;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class AnnotatedClientEndpointTest
{
    @ClientEndpoint(
        subprotocols = {"chat", "echo-whole"},
        decoders = {DateDecoder.class},
        encoders = {TimeEncoder.class},
        configurator = AnnotatedEndpointConfigurator.class)
    public static class AnnotatedEndpointClient
    {
        public Session session;
        public EndpointConfig config;

        @OnOpen
        public void onOpen(Session session, EndpointConfig config)
        {
            this.session = session;
            this.config = config;
        }

        @OnMessage(maxMessageSize = 111222)
        public void onText(Date date)
        {
            /* do nothing - just a test of DateDecoder wiring */
        }

        @OnMessage(maxMessageSize = 333444)
        public Date onBinary(ByteBuffer buf)
        {
            /* do nothing - just a test of TimeEncoder wiring */
            return null;
        }
    }

    public static class AnnotatedEndpointConfigurator extends ClientEndpointConfig.Configurator
    {
        @Override
        public void afterResponse(HandshakeResponse hr)
        {
            hr.getHeaders().put("X-Test", Collections.singletonList("Extra"));
            super.afterResponse(hr);
        }
    }

    private static CoreServer server;
    private static ClientEndpointConfig config;
    private static AnnotatedEndpointClient clientEndpoint;

    @BeforeAll
    public static void startEnv() throws Exception
    {
        // Server
        server = new CoreServer(new CoreServer.EchoNegotiator());

        // Start Server
        server.start();

        // Create Client
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server

        // Connect to Server
        clientEndpoint = new AnnotatedEndpointClient();
        assertNotNull(container.connectToServer(clientEndpoint, server.getWsUri()));
        assertNotNull(clientEndpoint.config);
        assertThat(clientEndpoint.config, instanceOf(ClientEndpointConfig.class));
        config = (ClientEndpointConfig)clientEndpoint.config;
    }

    @AfterAll
    public static void stopEnv()
    {
        // Close Session
        try
        {
            if (clientEndpoint.session != null)
                clientEndpoint.session.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        // Stop Server
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }
    }

    @Test
    public void testTextMax() throws Exception
    {
        assertThat(clientEndpoint.session.getMaxTextMessageBufferSize(), is(111222));
    }

    @Test
    public void testBinaryMax() throws Exception
    {
        assertThat(clientEndpoint.session.getMaxBinaryMessageBufferSize(), is(333444));
    }

    @Test
    public void testSubProtocols() throws Exception
    {
        String subprotocols = String.join(", ", config.getPreferredSubprotocols());
        assertThat(subprotocols, is("chat, echo-whole"));
    }

    @Test
    public void testDecoders() throws Exception
    {
        String decoders = config.getDecoders().stream().map(Class::getName).collect(joining(", "));
        assertThat(decoders, is(DateDecoder.class.getName()));
    }

    @Test
    public void testEncoders() throws Exception
    {
        String encoders = config.getEncoders().stream().map(Class::getName).collect(joining(", "));
        assertThat(encoders, is(TimeEncoder.class.getName()));
    }

    @Test
    public void testConfigurator() throws Exception
    {
        assertThat(config.getConfigurator(), instanceOf(AnnotatedEndpointConfigurator.class));
    }
}
