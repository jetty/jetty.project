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

package org.eclipse.jetty.websocket.jsr356.tests.client;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;
import javax.websocket.HandshakeResponse;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.websocket.jsr356.tests.CoreServer;
import org.eclipse.jetty.websocket.jsr356.tests.coders.DateDecoder;
import org.eclipse.jetty.websocket.jsr356.tests.coders.TimeEncoder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class AnnotatedEndpointConfigTest
{
    @ClientEndpoint(
            subprotocols = { "chat", "echo" },
            decoders = { DateDecoder.class },
            encoders = { TimeEncoder.class },
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
        public void onText(String msg)
        {
        /* do nothing */
        }
        
        @OnMessage(maxMessageSize = 333444)
        public void onBinary(ByteBuffer buf)
        {
        /* do nothing */
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
    private static ClientEndpointConfig ceconfig;
    private static EndpointConfig config;
    private static Session session;
    private static AnnotatedEndpointClient socket;

    @BeforeClass
    public static void startEnv() throws Exception
    {
        // Server
        server = new CoreServer(new CoreServer.EchoNegotiator());

        // Start Server
        server.start();

        // Connect client
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        socket = new AnnotatedEndpointClient();

        session = container.connectToServer(socket,server.getWsUri());
        assertThat("Session",session, notNullValue());

        config = socket.config;
        assertThat("EndpointConfig",config, notNullValue());
        assertThat("EndpointConfig",config, instanceOf(ClientEndpointConfig.class));

        ceconfig = (ClientEndpointConfig)config;
        assertThat("EndpointConfig",ceconfig, notNullValue());
    }

    @AfterClass
    public static void stopEnv()
    {
        // Disconnect client
        try
        {
            session.close();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }

        // Stop server
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
        assertThat("Client Text Max",
                socket.session.getMaxTextMessageBufferSize(),
                is(111222));
    }
    
    @Test
    public void testBinaryMax() throws Exception
    {
        assertThat("Client Binary Max",
                socket.session.getMaxBinaryMessageBufferSize(),
                is(333444));
    }
    
    @Test
    public void testSubProtocols() throws Exception
    {
        List<String> subprotocols = ceconfig.getPreferredSubprotocols();
        assertThat("Client Preferred SubProtocols",subprotocols, contains("chat","echo"));
    }

    @Test
    public void testDecoders() throws Exception
    {
        List<Class<? extends Decoder>> decoders = config.getDecoders();
        assertThat("Decoders",decoders, notNullValue());

        Class<?> expectedClass = DateDecoder.class;
        boolean hasExpectedDecoder = false;
        for (Class<? extends Decoder> decoder : decoders)
        {
            if (expectedClass.isAssignableFrom(decoder))
            {
                hasExpectedDecoder = true;
            }
        }

        assertTrue("Client Decoders has " + expectedClass.getName(),hasExpectedDecoder);
    }

    @Test
    public void testEncoders() throws Exception
    {
        List<Class<? extends Encoder>> encoders = config.getEncoders();
        assertThat("AvailableEncoders",encoders, notNullValue());

        Class<?> expectedClass = TimeEncoder.class;
        boolean hasExpectedEncoder = false;
        for (Class<? extends Encoder> encoder : encoders)
        {
            if (expectedClass.isAssignableFrom(encoder))
            {
                hasExpectedEncoder = true;
            }
        }

        assertTrue("Client AvailableEncoders has " + expectedClass.getName(),hasExpectedEncoder);
    }

    @Test
    public void testConfigurator() throws Exception
    {
        ClientEndpointConfig ceconfig = (ClientEndpointConfig)config;

        assertThat("Client Configurator",ceconfig.getConfigurator(), instanceOf(AnnotatedEndpointConfigurator.class));
    }
}
