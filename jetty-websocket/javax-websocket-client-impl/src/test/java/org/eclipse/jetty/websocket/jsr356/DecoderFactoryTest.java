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

import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;

import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.jsr356.decoders.ByteArrayDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.ByteBufferDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.DateDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.IntegerDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.LongDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.StringDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.TimeDecoder;
import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadata;
import org.eclipse.jetty.websocket.jsr356.samples.DualDecoder;
import org.eclipse.jetty.websocket.jsr356.samples.Fruit;
import org.eclipse.jetty.websocket.jsr356.samples.FruitDecoder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DecoderFactoryTest
{
    private DecoderFactory factory;

    private void assertDecoderType(Class<? extends Decoder> decoder, MessageType expectedMsgType, Type expectedObjType)
    {
        List<DecoderMetadata> metadatas = factory.getMetadata(decoder);
        Assert.assertThat("Metadatas.size",metadatas.size(),is(1));
        DecoderMetadata metadata = metadatas.get(0);
        Assert.assertThat("Metadata.messageType",metadata.getMessageType(),is(expectedMsgType));
        Assert.assertThat("Metadata.objectType",metadata.getObjectType(),is(expectedObjType));
    }

    private void assertMetadataFor(Class<?> type, Class<? extends Decoder> expectedDecoderClass, MessageType expectedType)
    {
        DecoderMetadata metadata = factory.getMetadataFor(type);
        Assert.assertEquals("metadata.decoderClass",metadata.getDecoderClass(),expectedDecoderClass);
        Assert.assertThat("metadata.messageType",metadata.getMessageType(),is(expectedType));
        Assert.assertEquals("metadata.objectType",metadata.getObjectType(),type);
    }

    @Before
    public void initDecoderFactory()
    {
        CommonContainer container = new ClientContainer();
        // create factory based on parent factory with primitives.
        factory = new DecoderFactory(container.getDecoderFactory());
    }

    @Test
    public void testGetByteArrayDecoder()
    {
        assertDecoderType(ByteArrayDecoder.class,MessageType.BINARY,byte[].class);
    }

    @Test
    public void testGetByteBufferDecoder()
    {
        assertDecoderType(ByteBufferDecoder.class,MessageType.BINARY,ByteBuffer.class);
    }

    @Test
    public void testGetFruitDecoder()
    {
        assertDecoderType(FruitDecoder.class,MessageType.TEXT,Fruit.class);
    }

    @Test
    public void testGetIntegerDecoder()
    {
        assertDecoderType(IntegerDecoder.class,MessageType.TEXT,Integer.TYPE);
    }

    @Test
    public void testGetLongDecoder()
    {
        assertDecoderType(LongDecoder.class,MessageType.TEXT,Long.TYPE);
    }

    @Test
    public void testGetMetadataForByteArray()
    {
        factory.register(ByteArrayDecoder.class);
        assertMetadataFor(byte[].class,ByteArrayDecoder.class,MessageType.BINARY);
    }

    @Test
    public void testGetMetadataForDate()
    {
        factory.register(DateDecoder.class);
        assertMetadataFor(Date.class,DateDecoder.class,MessageType.TEXT);
    }

    @Test
    public void testGetStringDecoder()
    {
        assertDecoderType(StringDecoder.class,MessageType.TEXT,String.class);
    }

    @Test
    public void testGetTextDecoder_Dual()
    {
        try
        {
            // has duplicated support for the same target Type
            factory.getMetadata(DualDecoder.class);
            Assert.fail("Should have thrown IllegalStateException for attempting to register Decoders with duplicate implementation");
        }
        catch (IllegalStateException e)
        {
            Assert.assertThat(e.getMessage(),containsString("Duplicate"));
        }
    }

    @Test
    public void testRegisterDuplicate()
    {
        // Register the DateDecoder (decodes java.util.Date)
        factory.register(DateDecoder.class);
        try
        {
            // Register the TimeDecoder (which also wants to decode java.util.Date)
            factory.register(TimeDecoder.class);
            Assert.fail("Should have thrown IllegalStateException for attempting to register Decoders with duplicate implementation");
        }
        catch (IllegalStateException e)
        {
            Assert.assertThat(e.getMessage(),containsString("Duplicate"));
        }
    }
}
