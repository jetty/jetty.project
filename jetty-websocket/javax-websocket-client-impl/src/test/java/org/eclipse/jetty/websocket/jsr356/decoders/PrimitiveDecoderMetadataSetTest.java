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

package org.eclipse.jetty.websocket.jsr356.decoders;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.jsr356.MessageType;
import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadata;
import org.junit.Assert;
import org.junit.Test;

public class PrimitiveDecoderMetadataSetTest
{
    private void assertClassEquals(String msg, Class<?> actual, Class<?> expected)
    {
        Assert.assertThat(msg,actual.getName(),is(expected.getName()));
    }

    private void assertDecoderType(Class<? extends Decoder> expectedDecoder, MessageType expectedMsgType, Class<?> type)
    {
        PrimitiveDecoderMetadataSet primitives = new PrimitiveDecoderMetadataSet();
        DecoderMetadata metadata = primitives.getMetadataByType(type);
        String prefix = String.format("Metadata By Type [%s]",type.getName());
        Assert.assertThat(prefix,metadata,notNullValue());

        assertClassEquals(prefix + ".coderClass",metadata.getCoderClass(),expectedDecoder);
        Assert.assertThat(prefix + ".messageType",metadata.getMessageType(),is(expectedMsgType));
    }

    @Test
    public void testGetByteArray()
    {
        assertDecoderType(ByteArrayDecoder.class,MessageType.BINARY,byte[].class);
    }
}
