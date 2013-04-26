//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;

import javax.websocket.DeploymentException;
import javax.websocket.MessageHandler;

import org.eclipse.jetty.websocket.jsr356.handlers.ByteArrayWholeHandler;
import org.eclipse.jetty.websocket.jsr356.handlers.ByteBufferPartialHandler;
import org.eclipse.jetty.websocket.jsr356.handlers.LongMessageHandler;
import org.eclipse.jetty.websocket.jsr356.handlers.StringWholeHandler;
import org.eclipse.jetty.websocket.jsr356.messages.MessageHandlerMetadataFactory;
import org.eclipse.jetty.websocket.jsr356.messages.MessageHandlerWrapper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MessageHandlersTest
{
    private MessageHandlerMetadataFactory factory;
    private Decoders decoders;

    @Before
    public void init() throws DeploymentException
    {
        SimpleClientEndpointConfig config = new SimpleClientEndpointConfig();
        DecoderMetadataFactory metadataFactory = new DecoderMetadataFactory();
        decoders = new Decoders(metadataFactory,config);
        factory = new MessageHandlerMetadataFactory(decoders);
    }

    @Test
    public void testGetBinaryHandler() throws DeploymentException
    {
        MessageHandlers mhs = new MessageHandlers(factory);
        mhs.add(new ByteBufferPartialHandler());
        MessageHandlerWrapper wrapper = mhs.getWrapper(MessageType.BINARY);
        Assert.assertThat("Binary Handler",wrapper.getHandler(),instanceOf(ByteBufferPartialHandler.class));
        Assert.assertEquals("Message Class",wrapper.getMetadata().getMessageClass(),ByteBuffer.class);
    }

    @Test
    public void testGetBothHandler() throws DeploymentException
    {
        MessageHandlers mhs = new MessageHandlers(factory);
        mhs.add(new StringWholeHandler());
        mhs.add(new ByteArrayWholeHandler());
        MessageHandlerWrapper wrapper = mhs.getWrapper(MessageType.TEXT);
        Assert.assertThat("Text Handler",wrapper.getHandler(),instanceOf(StringWholeHandler.class));
        Assert.assertEquals("Message Class",wrapper.getMetadata().getMessageClass(),String.class);
        wrapper = mhs.getWrapper(MessageType.BINARY);
        Assert.assertThat("Binary Handler",wrapper.getHandler(),instanceOf(ByteArrayWholeHandler.class));
        Assert.assertEquals("Message Class",wrapper.getMetadata().getMessageClass(),byte[].class);
    }

    @Test
    public void testGetTextHandler() throws DeploymentException
    {
        MessageHandlers mhs = new MessageHandlers(factory);
        mhs.add(new StringWholeHandler());
        MessageHandlerWrapper wrapper = mhs.getWrapper(MessageType.TEXT);
        Assert.assertThat("Text Handler",wrapper.getHandler(),instanceOf(StringWholeHandler.class));
        Assert.assertEquals("Message Class",wrapper.getMetadata().getMessageClass(),String.class);
    }

    @Test
    public void testReplaceTextHandler() throws DeploymentException
    {
        MessageHandlers mhs = new MessageHandlers(factory);
        MessageHandler oldText = new StringWholeHandler();
        mhs.add(oldText); // add a TEXT handler
        mhs.add(new ByteArrayWholeHandler()); // add BINARY handler
        mhs.remove(oldText); // remove original TEXT handler
        mhs.add(new LongMessageHandler()); // add new TEXT handler
        MessageHandlerWrapper wrapper = mhs.getWrapper(MessageType.BINARY);
        Assert.assertThat("Binary Handler",wrapper.getHandler(),instanceOf(ByteArrayWholeHandler.class));
        Assert.assertEquals("Message Class",wrapper.getMetadata().getMessageClass(),byte[].class);
        wrapper = mhs.getWrapper(MessageType.TEXT);
        Assert.assertThat("Text Handler",wrapper.getHandler(),instanceOf(LongMessageHandler.class));
        Assert.assertEquals("Message Class",wrapper.getMetadata().getMessageClass(),Long.class);
    }
}
