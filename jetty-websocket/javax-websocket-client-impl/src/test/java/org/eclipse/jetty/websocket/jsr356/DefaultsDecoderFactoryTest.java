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
import java.util.List;

import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.jsr356.DecoderMetadataFactory.DefaultsDecoderFactory;
import org.eclipse.jetty.websocket.jsr356.decoders.ByteArrayDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.ByteBufferDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.LongDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.StringDecoder;
import org.junit.Assert;
import org.junit.Test;

public class DefaultsDecoderFactoryTest
{
    private static DefaultsDecoderFactory factory = new DefaultsDecoderFactory();

    private void assertDefaultDecoderType(Class<? extends Decoder> decoder, MessageType expectedMsgType, Type expectedObjType)
    {
        List<DecoderMetadata> metadatas = factory.getMetadata(decoder);
        Assert.assertThat("Metadatas.size",metadatas.size(),is(1));
        DecoderMetadata metadata = metadatas.get(0);
        Assert.assertThat("Metadata.messageType",metadata.getMessageType(),is(expectedMsgType));
        Assert.assertThat("Metadata.objectType",metadata.getObjectType(),is(expectedObjType));
    }

    @Test
    public void testGetByteArrayDecoder()
    {
        assertDefaultDecoderType(ByteArrayDecoder.class,MessageType.BINARY,byte[].class);
    }

    @Test
    public void testGetByteBufferDecoder()
    {
        assertDefaultDecoderType(ByteBufferDecoder.class,MessageType.BINARY,ByteBuffer.class);
    }

    @Test
    public void testGetLongDecoder()
    {
        assertDefaultDecoderType(LongDecoder.class,MessageType.TEXT,Long.TYPE);
    }

    @Test
    public void testGetStringDecoder()
    {
        assertDefaultDecoderType(StringDecoder.class,MessageType.TEXT,String.class);
    }
}
