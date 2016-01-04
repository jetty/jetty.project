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

import javax.websocket.Encoder;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.jsr356.encoders.IntegerEncoder;
import org.eclipse.jetty.websocket.jsr356.encoders.LongEncoder;
import org.eclipse.jetty.websocket.jsr356.encoders.PrimitiveEncoderMetadataSet;
import org.eclipse.jetty.websocket.jsr356.metadata.EncoderMetadata;
import org.eclipse.jetty.websocket.jsr356.metadata.EncoderMetadataSet;
import org.eclipse.jetty.websocket.jsr356.samples.Fruit;
import org.eclipse.jetty.websocket.jsr356.samples.FruitBinaryEncoder;
import org.eclipse.jetty.websocket.jsr356.samples.FruitTextEncoder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests against the Encoders class
 */
public class EncoderFactoryTest
{
    private EncoderMetadataSet metadatas;
    private EncoderFactory factory;

    private void assertMetadataFor(Class<?> type, Class<? extends Encoder> expectedEncoderClass, MessageType expectedType)
    {
        EncoderMetadata metadata = factory.getMetadataFor(type);
        Assert.assertEquals("metadata.coderClass",metadata.getCoderClass(),expectedEncoderClass);
        Assert.assertThat("metadata.messageType",metadata.getMessageType(),is(expectedType));
        Assert.assertEquals("metadata.objectType",metadata.getObjectType(),type);
    }

    @Before
    public void initEncoderFactory()
    {
        WebSocketContainerScope containerScope = new SimpleContainerScope(WebSocketPolicy.newClientPolicy());
        
        EncoderFactory primitivesFactory = new EncoderFactory(containerScope,PrimitiveEncoderMetadataSet.INSTANCE);
        metadatas = new EncoderMetadataSet();
        factory = new EncoderFactory(containerScope,metadatas,primitivesFactory);
    }

    @Test
    public void testGetMetadataForFruitBinary()
    {
        metadatas.add(FruitBinaryEncoder.class);
        assertMetadataFor(Fruit.class,FruitBinaryEncoder.class,MessageType.BINARY);
    }

    @Test
    public void testGetMetadataForFruitText()
    {
        metadatas.add(FruitTextEncoder.class);
        assertMetadataFor(Fruit.class,FruitTextEncoder.class,MessageType.TEXT);
    }

    @Test
    public void testGetMetadataForInteger()
    {
        assertMetadataFor(Integer.TYPE,IntegerEncoder.class,MessageType.TEXT);
    }

    @Test
    public void testGetMetadataForLong()
    {
        assertMetadataFor(Long.TYPE,LongEncoder.class,MessageType.TEXT);
    }
}
