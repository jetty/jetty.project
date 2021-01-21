//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.List;
import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.jsr356.MessageType;
import org.eclipse.jetty.websocket.jsr356.decoders.BadDualDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.DateDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.IntegerDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.TimeDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.ValidDualDecoder;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DecoderMetadataSetTest
{
    private void assertMetadata(CoderMetadata<?> metadata, Class<?> expectedType, Class<?> expectedCoder, MessageType expectedMessageType)
    {
        assertEquals(expectedCoder, metadata.getCoderClass(), "metadata.coderClass");
        assertThat("metadata.messageType", metadata.getMessageType(), is(expectedMessageType));
        assertEquals(expectedType, metadata.getObjectType(), "metadata.objectType");
    }

    @Test
    public void testAddBadDualDecoders()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
        {
            DecoderMetadataSet coders = new DecoderMetadataSet();

            // has duplicated support for the same target Type
            coders.add(BadDualDecoder.class);
            // Should have thrown IllegalStateException for attempting to register Decoders with duplicate implementation
        });
        assertThat(e.getMessage(), containsString("Duplicate"));
    }

    @Test
    public void testAddDuplicate()
    {
        DecoderMetadataSet coders = new DecoderMetadataSet();

        // Add DateDecoder (decodes java.util.Date)
        coders.add(DateDecoder.class);

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
        {
            // Add TimeDecoder (which also wants to decode java.util.Date)
            coders.add(TimeDecoder.class);
            // Should have thrown IllegalStateException for attempting to register Decoders with duplicate implementation
        });
        assertThat(e.getMessage(), containsString("Duplicate"));
    }

    @Test
    public void testAddGetCoder()
    {
        DecoderMetadataSet coders = new DecoderMetadataSet();

        coders.add(IntegerDecoder.class);
        Class<? extends Decoder> actualClazz = coders.getCoder(Integer.class);
        assertEquals(IntegerDecoder.class, actualClazz, "Coder Class");
    }

    @Test
    public void testAddGetMetadataByImpl()
    {
        DecoderMetadataSet coders = new DecoderMetadataSet();

        coders.add(IntegerDecoder.class);
        List<DecoderMetadata> metadatas = coders.getMetadataByImplementation(IntegerDecoder.class);
        assertThat("Metadatas (by impl) count", metadatas.size(), is(1));
        DecoderMetadata metadata = metadatas.get(0);
        assertMetadata(metadata, Integer.class, IntegerDecoder.class, MessageType.TEXT);
    }

    @Test
    public void testAddGetMetadataByType()
    {
        DecoderMetadataSet coders = new DecoderMetadataSet();

        coders.add(IntegerDecoder.class);
        DecoderMetadata metadata = coders.getMetadataByType(Integer.class);
        assertMetadata(metadata, Integer.class, IntegerDecoder.class, MessageType.TEXT);
    }

    @Test
    public void testAddValidDualDecoders()
    {
        DecoderMetadataSet coders = new DecoderMetadataSet();

        coders.add(ValidDualDecoder.class);

        List<Class<? extends Decoder>> decodersList = coders.getList();
        assertThat("Decoder List", decodersList, notNullValue());
        assertThat("Decoder List count", decodersList.size(), is(2));

        DecoderMetadata metadata;
        metadata = coders.getMetadataByType(Integer.class);
        assertMetadata(metadata, Integer.class, ValidDualDecoder.class, MessageType.TEXT);

        metadata = coders.getMetadataByType(Long.class);
        assertMetadata(metadata, Long.class, ValidDualDecoder.class, MessageType.BINARY);
    }
}
