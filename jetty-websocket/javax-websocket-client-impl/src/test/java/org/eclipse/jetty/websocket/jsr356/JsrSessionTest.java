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

package org.eclipse.jetty.websocket.jsr356;

import java.net.URI;
import java.nio.ByteBuffer;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.MessageHandler;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.test.DummyConnection;
import org.eclipse.jetty.websocket.jsr356.client.EmptyClientEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.client.SimpleEndpointMetadata;
import org.eclipse.jetty.websocket.jsr356.endpoints.EndpointInstance;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrEndpointEventDriver;
import org.eclipse.jetty.websocket.jsr356.handlers.ByteArrayWholeHandler;
import org.eclipse.jetty.websocket.jsr356.handlers.ByteBufferPartialHandler;
import org.eclipse.jetty.websocket.jsr356.handlers.LongMessageHandler;
import org.eclipse.jetty.websocket.jsr356.handlers.StringWholeHandler;
import org.eclipse.jetty.websocket.jsr356.samples.DummyEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class JsrSessionTest
{
    private ClientContainer container;
    private JsrSession session;

    @BeforeEach
    public void initSession()
    {
        container = new ClientContainer();
        String id = JsrSessionTest.class.getSimpleName();
        URI requestURI = URI.create("ws://localhost/" + id);
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        ClientEndpointConfig config = new EmptyClientEndpointConfig();
        DummyEndpoint websocket = new DummyEndpoint();
        SimpleEndpointMetadata metadata = new SimpleEndpointMetadata(websocket.getClass());
        // Executor executor = null;

        EndpointInstance ei = new EndpointInstance(websocket, config, metadata);

        EventDriver driver = new JsrEndpointEventDriver(policy, ei);
        DummyConnection connection = new DummyConnection();
        session = new JsrSession(container, id, requestURI, driver, connection);
    }

    @Test
    public void testMessageHandlerBinary() throws DeploymentException
    {
        session.addMessageHandler(new ByteBufferPartialHandler());
        MessageHandlerWrapper wrapper = session.getMessageHandlerWrapper(MessageType.BINARY);
        assertThat("Binary Handler", wrapper.getHandler(), instanceOf(ByteBufferPartialHandler.class));
        assertEquals(wrapper.getMetadata().getMessageClass(), ByteBuffer.class, "Message Class");
    }

    @Test
    public void testMessageHandlerBoth() throws DeploymentException
    {
        session.addMessageHandler(new StringWholeHandler());
        session.addMessageHandler(new ByteArrayWholeHandler());
        MessageHandlerWrapper wrapper = session.getMessageHandlerWrapper(MessageType.TEXT);
        assertThat("Text Handler", wrapper.getHandler(), instanceOf(StringWholeHandler.class));
        assertEquals(wrapper.getMetadata().getMessageClass(), String.class, "Message Class");
        wrapper = session.getMessageHandlerWrapper(MessageType.BINARY);
        assertThat("Binary Handler", wrapper.getHandler(), instanceOf(ByteArrayWholeHandler.class));
        assertEquals(wrapper.getMetadata().getMessageClass(), byte[].class, "Message Class");
    }

    @Test
    public void testMessageHandlerReplaceTextHandler() throws DeploymentException
    {
        MessageHandler oldText = new StringWholeHandler();
        session.addMessageHandler(oldText); // add a TEXT handler
        session.addMessageHandler(new ByteArrayWholeHandler()); // add BINARY handler
        session.removeMessageHandler(oldText); // remove original TEXT handler
        session.addMessageHandler(new LongMessageHandler()); // add new TEXT handler
        MessageHandlerWrapper wrapper = session.getMessageHandlerWrapper(MessageType.BINARY);
        assertThat("Binary Handler", wrapper.getHandler(), instanceOf(ByteArrayWholeHandler.class));
        assertEquals(wrapper.getMetadata().getMessageClass(), byte[].class, "Message Class");
        wrapper = session.getMessageHandlerWrapper(MessageType.TEXT);
        assertThat("Text Handler", wrapper.getHandler(), instanceOf(LongMessageHandler.class));
        assertEquals(wrapper.getMetadata().getMessageClass(), Long.class, "Message Class");
    }

    @Test
    public void testMessageHandlerText() throws DeploymentException
    {
        session.addMessageHandler(new StringWholeHandler());
        MessageHandlerWrapper wrapper = session.getMessageHandlerWrapper(MessageType.TEXT);
        assertThat("Text Handler", wrapper.getHandler(), instanceOf(StringWholeHandler.class));
        assertEquals(wrapper.getMetadata().getMessageClass(), String.class, "Message Class");
    }
}
