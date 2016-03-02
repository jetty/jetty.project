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

package org.eclipse.jetty.websocket.jsr356;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.net.URI;
import java.util.List;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.jsr356.decoders.DateDecoder;
import org.eclipse.jetty.websocket.jsr356.encoders.TimeEncoder;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class AnnotatedEndpointConfigTest
{
    private static Server server;
    private static EchoHandler handler;
    private static URI serverUri;
    private static ClientEndpointConfig ceconfig;
    private static EndpointConfig config;
    private static Session session;
    private static AnnotatedEndpointClient socket;

    @BeforeClass
    public static void startEnv() throws Exception
    {
        // Server

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

        // Connect client

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        socket = new AnnotatedEndpointClient();

        session = container.connectToServer(socket,serverUri);
        Assert.assertThat("Session",session,notNullValue());

        config = socket.config;
        Assert.assertThat("EndpointConfig",config,notNullValue());
        Assert.assertThat("EndpointConfig",config,instanceOf(ClientEndpointConfig.class));

        ceconfig = (ClientEndpointConfig)config;
        Assert.assertThat("EndpointConfig",ceconfig,notNullValue());
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
        Assert.assertThat("Client Text Max",
                socket.session.getMaxTextMessageBufferSize(),
                is(111222));
    }
    
    @Test
    public void testBinaryMax() throws Exception
    {
        Assert.assertThat("Client Binary Max",
                socket.session.getMaxBinaryMessageBufferSize(),
                is(333444));
    }
    
    @Test
    public void testSubProtocols() throws Exception
    {
        List<String> subprotocols = ceconfig.getPreferredSubprotocols();
        Assert.assertThat("Client Preferred SubProtocols",subprotocols,contains("chat","echo"));
    }

    @Test
    public void testDecoders() throws Exception
    {
        List<Class<? extends Decoder>> decoders = config.getDecoders();
        Assert.assertThat("Decoders",decoders,notNullValue());

        Class<?> expectedClass = DateDecoder.class;
        boolean hasExpectedDecoder = false;
        for (Class<? extends Decoder> decoder : decoders)
        {
            if (expectedClass.isAssignableFrom(decoder))
            {
                hasExpectedDecoder = true;
            }
        }

        Assert.assertTrue("Client Decoders has " + expectedClass.getName(),hasExpectedDecoder);
    }

    @Test
    public void testEncoders() throws Exception
    {
        List<Class<? extends Encoder>> encoders = config.getEncoders();
        Assert.assertThat("Encoders",encoders,notNullValue());

        Class<?> expectedClass = TimeEncoder.class;
        boolean hasExpectedEncoder = false;
        for (Class<? extends Encoder> encoder : encoders)
        {
            if (expectedClass.isAssignableFrom(encoder))
            {
                hasExpectedEncoder = true;
            }
        }

        Assert.assertTrue("Client Encoders has " + expectedClass.getName(),hasExpectedEncoder);
    }

    @Test
    public void testConfigurator() throws Exception
    {
        ClientEndpointConfig ceconfig = (ClientEndpointConfig)config;

        Assert.assertThat("Client Configurator",ceconfig.getConfigurator(),instanceOf(AnnotatedEndpointConfigurator.class));
    }
}
