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

package org.eclipse.jetty.websocket.tests.client.jsr356;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.MessageHandler;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.jsr356.client.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.ConfiguredEndpoint;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.jsr356.client.EmptyClientEndpointConfig;
import org.eclipse.jetty.websocket.tests.LocalWebSocketConnection;
import org.eclipse.jetty.websocket.tests.jsr356.handlers.ByteArrayWholeHandler;
import org.eclipse.jetty.websocket.tests.jsr356.handlers.ByteBufferPartialHandler;
import org.eclipse.jetty.websocket.tests.jsr356.handlers.LongMessageHandler;
import org.eclipse.jetty.websocket.tests.jsr356.handlers.StringWholeHandler;
import org.eclipse.jetty.websocket.tests.jsr356.endpoints.DummyEndpoint;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class JsrSessionTest
{
    public ByteBufferPool bufferPool = new LeakTrackingByteBufferPool(new MappedByteBufferPool());
    
    private SimpleContainerScope containerScope;
    private ClientContainer container;
    private JavaxWebSocketSession session;
    
    @Before
    public void initSession() throws Exception
    {
        String id = JsrSessionTest.class.getSimpleName();
        URI requestURI = URI.create("ws://localhost/" + id);
        
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        
        // Container
        containerScope = new SimpleContainerScope(policy, bufferPool);
        containerScope.start();
        container = new ClientContainer(containerScope);
        container.start();
        LocalWebSocketConnection connection = new LocalWebSocketConnection(bufferPool);
        ClientEndpointConfig config = new EmptyClientEndpointConfig();
        ConfiguredEndpoint ei = new ConfiguredEndpoint(new DummyEndpoint(), config);
        
        // Session
        session = new JavaxWebSocketSession(container, id, requestURI, ei, connection);
        session.start();
    }
    
    @After
    public void stopSession() throws Exception
    {
        session.stop();
        container.stop();
    }
    
    @Test
    public void testMessageHandlerBinary() throws DeploymentException
    {
        session.addMessageHandler(new ByteBufferPartialHandler());
    }
    
    @Test
    public void testMessageHandlerBoth() throws DeploymentException
    {
        session.addMessageHandler(new StringWholeHandler());
        session.addMessageHandler(new ByteArrayWholeHandler());
    }
    
    @Test
    public void testMessageHandlerReplaceTextHandler() throws DeploymentException
    {
        MessageHandler strHandler = new StringWholeHandler();
        session.addMessageHandler(strHandler); // add a TEXT handler
        session.addMessageHandler(new ByteArrayWholeHandler()); // add BINARY handler
        session.removeMessageHandler(strHandler); // remove original TEXT handler
        session.addMessageHandler(new LongMessageHandler()); // add new TEXT handler
    }
    
    @Test
    public void testMessageHandlerText() throws DeploymentException
    {
        session.addMessageHandler(new StringWholeHandler());
    }
    
    /**
     * Test Java 8 Lamba of {@link javax.websocket.MessageHandler.Whole}
     */
    @Test
    public void testMessageHandler_11_Whole() throws DeploymentException
    {
        final List<String> received = new ArrayList<>();
        
        // Whole Message
        session.addMessageHandler(String.class, (msg) -> received.add(msg));
        
        session.connect();
        session.onOpen();
    
        FrameCallback callback = new FrameCallback.Adapter();
        
        session.incomingFrame(new TextFrame().setPayload("G'day").setFin(true), callback);
        session.incomingFrame(new TextFrame().setPayload("Hello World").setFin(true), callback);
        
        assertThat("Received msgs", received.size(), is(2));
        assertThat("Received Message[0]", received.get(0), is("G'day"));
        assertThat("Received Message[1]", received.get(1), is("Hello World"));
        
        session.close();
    }
    
    /**
     * Test Java 8 Lamba of {@link javax.websocket.MessageHandler.Partial}
     */
    @Test
    public void testMessageHandler_11_Partial() throws DeploymentException
    {
        final List<Object[]> received = new ArrayList<>();
        
        // Partial Message
        session.addMessageHandler(ByteBuffer.class, (partialMsg, isLast) ->
        {
            ByteBuffer copy = ByteBuffer.allocate(partialMsg.remaining());
            copy.put(partialMsg);
            copy.flip();
            received.add(new Object[]{copy, isLast});
        });
    
        session.connect();
        session.onOpen();
        
        FrameCallback callback = new FrameCallback.Adapter();
        
        session.incomingFrame(new BinaryFrame().setPayload("G'day").setFin(false), callback);
        session.incomingFrame(new ContinuationFrame().setPayload(" World").setFin(true), callback);
        
        assertThat("Received partial", received.size(), is(2));
        assertThat("Received Message[0].buffer", BufferUtil.toUTF8String((ByteBuffer) received.get(0)[0]), is("G'day"));
        assertThat("Received Message[0].last", received.get(0)[1], is(false));
        assertThat("Received Message[1].buffer", BufferUtil.toUTF8String((ByteBuffer) received.get(1)[0]), is(" World"));
        assertThat("Received Message[1].last", received.get(1)[1], is(true));
        
        session.close();
    }
}
