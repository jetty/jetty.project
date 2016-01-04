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

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.util.Date;

import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.jsr356.decoders.ByteArrayDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.ByteBufferDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.DateDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.IntegerDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.LongDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.PrimitiveDecoderMetadataSet;
import org.eclipse.jetty.websocket.jsr356.decoders.StringDecoder;
import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadata;
import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadataSet;
import org.eclipse.jetty.websocket.jsr356.samples.Fruit;
import org.eclipse.jetty.websocket.jsr356.samples.FruitDecoder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DecoderFactoryTest
{
    private DecoderMetadataSet metadatas;
    private DecoderFactory factory;

    private void assertMetadataFor(Class<?> type, Class<? extends Decoder> expectedDecoderClass, MessageType expectedType)
    {
        DecoderMetadata metadata = factory.getMetadataFor(type);
        Assert.assertEquals("metadata.coderClass",metadata.getCoderClass(),expectedDecoderClass);
        Assert.assertThat("metadata.messageType",metadata.getMessageType(),is(expectedType));
        Assert.assertEquals("metadata.objectType",metadata.getObjectType(),type);
    }

    @Before
    public void initDecoderFactory()
    {
        WebSocketContainerScope containerScope = new SimpleContainerScope(WebSocketPolicy.newClientPolicy());
        
        DecoderFactory primitivesFactory = new DecoderFactory(containerScope,PrimitiveDecoderMetadataSet.INSTANCE);
        metadatas = new DecoderMetadataSet();
        factory = new DecoderFactory(containerScope,metadatas,primitivesFactory);
    }

    @Test
    public void testGetMetadataForByteArray()
    {
        assertMetadataFor(byte[].class,ByteArrayDecoder.class,MessageType.BINARY);
    }

    @Test
    public void testGetMetadataForByteBuffer()
    {
        assertMetadataFor(ByteBuffer.class,ByteBufferDecoder.class,MessageType.BINARY);
    }

    @Test
    public void testGetMetadataForDate()
    {
        metadatas.add(DateDecoder.class);
        assertMetadataFor(Date.class,DateDecoder.class,MessageType.TEXT);
    }

    @Test
    public void testGetMetadataForFruit()
    {
        metadatas.add(FruitDecoder.class);
        assertMetadataFor(Fruit.class,FruitDecoder.class,MessageType.TEXT);
    }

    @Test
    public void testGetMetadataForInteger()
    {
        assertMetadataFor(Integer.TYPE,IntegerDecoder.class,MessageType.TEXT);
    }

    @Test
    public void testGetMetadataForLong()
    {
        assertMetadataFor(Long.TYPE,LongDecoder.class,MessageType.TEXT);
    }

    @Test
    public void testGetStringDecoder()
    {
        assertMetadataFor(String.class,StringDecoder.class,MessageType.TEXT);
    }
}
