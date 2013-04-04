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

package org.eclipse.jetty.websocket.jsr356.messages;

import static org.hamcrest.Matchers.*;

import java.lang.reflect.Type;
import java.util.List;

import javax.websocket.DeploymentException;

import org.eclipse.jetty.websocket.jsr356.DecoderMetadataFactory;
import org.eclipse.jetty.websocket.jsr356.Decoders;
import org.eclipse.jetty.websocket.jsr356.MessageType;
import org.eclipse.jetty.websocket.jsr356.SimpleClientEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.handlers.ByteArrayPartialHandler;
import org.eclipse.jetty.websocket.jsr356.handlers.StringPartialHandler;
import org.eclipse.jetty.websocket.jsr356.messages.MessageHandlerMetadata;
import org.eclipse.jetty.websocket.jsr356.messages.MessageHandlerMetadataFactory;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MessageHandlerMetadataFactoryTest
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
    public void testByteArrayPartial() throws DeploymentException
    {
        List<MessageHandlerMetadata> metadatas = factory.getMetadata(ByteArrayPartialHandler.class);
        Assert.assertThat("Metadata.list.size",metadatas.size(),is(1));

        MessageHandlerMetadata metadata = metadatas.get(0);
        Assert.assertThat("Message Type",metadata.getMessageType(),is(MessageType.BINARY));
        Assert.assertThat("Message Class",metadata.getMessageClass(),is((Type)byte[].class));
    }

    @Test
    public void testStringPartial() throws DeploymentException
    {
        List<MessageHandlerMetadata> metadatas = factory.getMetadata(StringPartialHandler.class);
        Assert.assertThat("Metadata.list.size",metadatas.size(),is(1));

        MessageHandlerMetadata metadata = metadatas.get(0);
        Assert.assertThat("Message Type",metadata.getMessageType(),is(MessageType.TEXT));
        Assert.assertThat("Message Class",metadata.getMessageClass(),is((Type)String.class));
    }
}
