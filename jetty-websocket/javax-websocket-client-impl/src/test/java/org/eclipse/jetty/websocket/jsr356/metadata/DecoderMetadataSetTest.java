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

package org.eclipse.jetty.websocket.jsr356.metadata;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.util.List;

import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.jsr356.MessageType;
import org.eclipse.jetty.websocket.jsr356.decoders.BadDualDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.DateDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.IntegerDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.TimeDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.ValidDualDecoder;
import org.junit.Assert;
import org.junit.Test;

public class DecoderMetadataSetTest
{
    private void assertMetadata(CoderMetadata<?> metadata, Class<?> expectedType, Class<?> expectedCoder, MessageType expectedMessageType)
    {
        Assert.assertEquals("metadata.coderClass",expectedCoder,metadata.getCoderClass());
        Assert.assertThat("metadata.messageType",metadata.getMessageType(),is(expectedMessageType));
        Assert.assertEquals("metadata.objectType",expectedType,metadata.getObjectType());
    }

    @Test
    public void testAddBadDualDecoders()
    {
        try
        {
            DecoderMetadataSet coders = new DecoderMetadataSet();

            // has duplicated support for the same target Type
            coders.add(BadDualDecoder.class);
            Assert.fail("Should have thrown IllegalStateException for attempting to register Decoders with duplicate implementation");
        }
        catch (IllegalStateException e)
        {
            Assert.assertThat(e.getMessage(),containsString("Duplicate"));
        }
    }

    @Test
    public void testAddDuplicate()
    {
        DecoderMetadataSet coders = new DecoderMetadataSet();

        // Add DateDecoder (decodes java.util.Date)
        coders.add(DateDecoder.class);

        try
        {
            // Add TimeDecoder (which also wants to decode java.util.Date)
            coders.add(TimeDecoder.class);
            Assert.fail("Should have thrown IllegalStateException for attempting to register Decoders with duplicate implementation");
        }
        catch (IllegalStateException e)
        {
            Assert.assertThat(e.getMessage(),containsString("Duplicate"));
        }
    }

    @Test
    public void testAddGetCoder()
    {
        DecoderMetadataSet coders = new DecoderMetadataSet();

        coders.add(IntegerDecoder.class);
        Class<? extends Decoder> actualClazz = coders.getCoder(Integer.class);
        Assert.assertEquals("Coder Class",IntegerDecoder.class,actualClazz);
    }

    @Test
    public void testAddGetMetadataByImpl()
    {
        DecoderMetadataSet coders = new DecoderMetadataSet();

        coders.add(IntegerDecoder.class);
        List<DecoderMetadata> metadatas = coders.getMetadataByImplementation(IntegerDecoder.class);
        Assert.assertThat("Metadatas (by impl) count",metadatas.size(),is(1));
        DecoderMetadata metadata = metadatas.get(0);
        assertMetadata(metadata,Integer.class,IntegerDecoder.class,MessageType.TEXT);
    }

    @Test
    public void testAddGetMetadataByType()
    {
        DecoderMetadataSet coders = new DecoderMetadataSet();

        coders.add(IntegerDecoder.class);
        DecoderMetadata metadata = coders.getMetadataByType(Integer.class);
        assertMetadata(metadata,Integer.class,IntegerDecoder.class,MessageType.TEXT);
    }

    @Test
    public void testAddValidDualDecoders()
    {
        DecoderMetadataSet coders = new DecoderMetadataSet();

        coders.add(ValidDualDecoder.class);

        List<Class<? extends Decoder>> decodersList = coders.getList();
        Assert.assertThat("Decoder List",decodersList,notNullValue());
        Assert.assertThat("Decoder List count",decodersList.size(),is(2));

        DecoderMetadata metadata;
        metadata = coders.getMetadataByType(Integer.class);
        assertMetadata(metadata,Integer.class,ValidDualDecoder.class,MessageType.TEXT);

        metadata = coders.getMetadataByType(Long.class);
        assertMetadata(metadata,Long.class,ValidDualDecoder.class,MessageType.BINARY);
    }
}
