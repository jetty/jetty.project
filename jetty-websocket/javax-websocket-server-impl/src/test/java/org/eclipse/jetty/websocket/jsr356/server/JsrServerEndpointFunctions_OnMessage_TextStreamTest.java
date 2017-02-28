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

package org.eclipse.jetty.websocket.jsr356.server;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.EndpointConfig;
import javax.websocket.OnMessage;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.function.EndpointFunctions;
import org.eclipse.jetty.websocket.common.test.DummyConnection;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.ConfiguredEndpoint;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.client.EmptyClientEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class JsrServerEndpointFunctions_OnMessage_TextStreamTest
{
    private static ClientContainer container;
    
    @BeforeClass
    public static void initContainer()
    {
        container = new ClientContainer();
    }
    
    @Rule
    public ExpectedException expectedException = ExpectedException.none();
    
    private AvailableEncoders encoders;
    private AvailableDecoders decoders;
    private Map<String, String> uriParams = new HashMap<>();
    private EndpointConfig endpointConfig;
    
    public JsrServerEndpointFunctions_OnMessage_TextStreamTest()
    {
        endpointConfig = new EmptyClientEndpointConfig();
        encoders = new AvailableEncoders(endpointConfig);
        decoders = new AvailableDecoders(endpointConfig);
        uriParams = new HashMap<>();
        uriParams.put("param", "foo");
    }
    
    public JsrSession newSession(Object websocket)
    {
        String id = JsrServerEndpointFunctions_OnMessage_TextStreamTest.class.getSimpleName();
        URI requestURI = URI.create("ws://localhost/" + id);
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        DummyConnection connection = new DummyConnection(policy);
        ClientEndpointConfig config = new EmptyClientEndpointConfig();
        ConfiguredEndpoint ei = new ConfiguredEndpoint(websocket, config);
        return new JsrSession(container, id, requestURI, ei, connection);
    }
    
    @SuppressWarnings("Duplicates")
    private TrackingSocket performOnMessageInvocation(TrackingSocket socket, Function<EndpointFunctions, Void> func) throws Exception
    {
        // Establish endpoint function
        JsrServerEndpointFunctions endpointFunctions = new JsrServerEndpointFunctions(
                socket, container.getPolicy(),
                container.getExecutor(),
                encoders,
                decoders,
                uriParams,
                endpointConfig
        );
        endpointFunctions.start();
        
        // This invocation is the same for all tests
        endpointFunctions.onOpen(newSession(socket));
        
        func.apply(endpointFunctions);
        
        return socket;
    }

    @ServerEndpoint("/msg")
    public static class MessageStreamSocket extends TrackingSocket
    {
        @OnMessage
        public void onMessage(Reader stream)
        {
            try
            {
                String msg = IO.toString(stream);
                addEvent("onMessage(%s) = \"%s\"", stream.getClass().getSimpleName(), msg);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }
    
    @Test
    public void testInvokeMessageStream() throws Exception
    {
        TrackingSocket socket = performOnMessageInvocation(new MessageStreamSocket(), (endpoint) ->
        {
            endpoint.onText(BufferUtil.toBuffer("Hello World", StandardCharsets.UTF_8), true);
            return null;
        });
        socket.assertEvent("onMessage(MessageReader) = \"Hello World\"");
    }
    
    @ServerEndpoint("/msg/{param}")
    public static class MessageStreamParamSocket extends TrackingSocket
    {
        @OnMessage
        public String onMessage(Reader stream, @PathParam("param") String param) throws IOException
        {
            try
            {
                String msg = IO.toString(stream);
                addEvent("onMessage(%s,%s) = \"%s\"", stream.getClass().getSimpleName(), param, msg);
                return msg;
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }
    
    @Test
    public void testInvokeMessageStreamParam() throws Exception
    {
        TrackingSocket socket = performOnMessageInvocation(new MessageStreamParamSocket(), (endpoint) ->
        {
            endpoint.onText(BufferUtil.toBuffer("Hello World", StandardCharsets.UTF_8), true);
            return null;
        });
        socket.assertEvent("onMessage(MessageReader,foo) = \"Hello World\"");
    }
    
}
