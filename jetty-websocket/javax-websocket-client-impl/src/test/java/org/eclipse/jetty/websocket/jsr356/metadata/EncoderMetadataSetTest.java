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

import javax.websocket.Encoder;

import org.eclipse.jetty.websocket.jsr356.MessageType;
import org.eclipse.jetty.websocket.jsr356.encoders.BadDualEncoder;
import org.eclipse.jetty.websocket.jsr356.encoders.DateEncoder;
import org.eclipse.jetty.websocket.jsr356.encoders.IntegerEncoder;
import org.eclipse.jetty.websocket.jsr356.encoders.TimeEncoder;
import org.eclipse.jetty.websocket.jsr356.encoders.ValidDualEncoder;
import org.junit.Assert;
import org.junit.Test;

public class EncoderMetadataSetTest
{
    private void assertMetadata(CoderMetadata<?> metadata, Class<?> expectedType, Class<?> expectedCoder, MessageType expectedMessageType)
    {
        Assert.assertEquals("metadata.coderClass",expectedCoder,metadata.getCoderClass());
        Assert.assertThat("metadata.messageType",metadata.getMessageType(),is(expectedMessageType));
        Assert.assertEquals("metadata.objectType",expectedType,metadata.getObjectType());
    }

    @Test
    public void testAddBadDualEncoders()
    {
        try
        {
            EncoderMetadataSet coders = new EncoderMetadataSet();

            // has duplicated support for the same target Type
            coders.add(BadDualEncoder.class);
            Assert.fail("Should have thrown IllegalStateException for attempting to register Encoders with duplicate implementation");
        }
        catch (IllegalStateException e)
        {
            Assert.assertThat(e.getMessage(),containsString("Duplicate"));
        }
    }

    @Test
    public void testAddDuplicate()
    {
        EncoderMetadataSet coders = new EncoderMetadataSet();

        // Add DateEncoder (decodes java.util.Date)
        coders.add(DateEncoder.class);

        try
        {
            // Add TimeEncoder (which also wants to decode java.util.Date)
            coders.add(TimeEncoder.class);
            Assert.fail("Should have thrown IllegalStateException for attempting to register Encoders with duplicate implementation");
        }
        catch (IllegalStateException e)
        {
            Assert.assertThat(e.getMessage(),containsString("Duplicate"));
        }
    }

    @Test
    public void testAddGetCoder()
    {
        EncoderMetadataSet coders = new EncoderMetadataSet();

        coders.add(IntegerEncoder.class);
        Class<? extends Encoder> actualClazz = coders.getCoder(Integer.class);
        Assert.assertEquals("Coder Class",IntegerEncoder.class,actualClazz);
    }

    @Test
    public void testAddGetMetadataByImpl()
    {
        EncoderMetadataSet coders = new EncoderMetadataSet();

        coders.add(IntegerEncoder.class);
        List<EncoderMetadata> metadatas = coders.getMetadataByImplementation(IntegerEncoder.class);
        Assert.assertThat("Metadatas (by impl) count",metadatas.size(),is(1));
        EncoderMetadata metadata = metadatas.get(0);
        assertMetadata(metadata,Integer.class,IntegerEncoder.class,MessageType.TEXT);
    }

    @Test
    public void testAddGetMetadataByType()
    {
        EncoderMetadataSet coders = new EncoderMetadataSet();

        coders.add(IntegerEncoder.class);
        EncoderMetadata metadata = coders.getMetadataByType(Integer.class);
        assertMetadata(metadata,Integer.class,IntegerEncoder.class,MessageType.TEXT);
    }

    @Test
    public void testAddValidDualEncoders()
    {
        EncoderMetadataSet coders = new EncoderMetadataSet();

        coders.add(ValidDualEncoder.class);

        List<Class<? extends Encoder>> EncodersList = coders.getList();
        Assert.assertThat("Encoder List",EncodersList,notNullValue());
        Assert.assertThat("Encoder List count",EncodersList.size(),is(2));

        EncoderMetadata metadata;
        metadata = coders.getMetadataByType(Integer.class);
        assertMetadata(metadata,Integer.class,ValidDualEncoder.class,MessageType.TEXT);

        metadata = coders.getMetadataByType(Long.class);
        assertMetadata(metadata,Long.class,ValidDualEncoder.class,MessageType.BINARY);
    }
}
