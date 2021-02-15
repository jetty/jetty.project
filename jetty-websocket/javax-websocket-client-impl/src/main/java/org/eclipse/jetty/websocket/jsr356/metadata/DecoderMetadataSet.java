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

import java.util.ArrayList;
import java.util.List;
import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.eclipse.jetty.websocket.jsr356.MessageType;

public class DecoderMetadataSet extends CoderMetadataSet<Decoder, DecoderMetadata>
{
    @Override
    protected List<DecoderMetadata> discover(Class<? extends Decoder> decoder)
    {
        List<DecoderMetadata> metadatas = new ArrayList<>();

        if (Decoder.Binary.class.isAssignableFrom(decoder))
        {
            Class<?> objType = getDecoderType(decoder, Decoder.Binary.class);
            metadatas.add(new DecoderMetadata(decoder, objType, MessageType.BINARY, false));
        }
        if (Decoder.BinaryStream.class.isAssignableFrom(decoder))
        {
            Class<?> objType = getDecoderType(decoder, Decoder.BinaryStream.class);
            metadatas.add(new DecoderMetadata(decoder, objType, MessageType.BINARY, true));
        }
        if (Decoder.Text.class.isAssignableFrom(decoder))
        {
            Class<?> objType = getDecoderType(decoder, Decoder.Text.class);
            metadatas.add(new DecoderMetadata(decoder, objType, MessageType.TEXT, false));
        }
        if (Decoder.TextStream.class.isAssignableFrom(decoder))
        {
            Class<?> objType = getDecoderType(decoder, Decoder.TextStream.class);
            metadatas.add(new DecoderMetadata(decoder, objType, MessageType.TEXT, true));
        }

        if (!ReflectUtils.isDefaultConstructable(decoder))
        {
            throw new InvalidSignatureException("Decoder must have public, no-args constructor: " + decoder.getName());
        }

        if (metadatas.size() <= 0)
        {
            throw new InvalidSignatureException("Not a valid Decoder class: " + decoder.getName());
        }

        return metadatas;
    }

    private Class<?> getDecoderType(Class<? extends Decoder> decoder, Class<?> interfaceClass)
    {
        Class<?> decoderClass = ReflectUtils.findGenericClassFor(decoder, interfaceClass);
        if (decoderClass == null)
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid type declared for interface ");
            err.append(interfaceClass.getName());
            err.append(" on class ");
            err.append(decoder);
            throw new InvalidWebSocketException(err.toString());
        }
        return decoderClass;
    }

    protected final void register(Class<?> type, Class<? extends Decoder> decoder, MessageType msgType, boolean streamed)
    {
        DecoderMetadata metadata = new DecoderMetadata(decoder, type, msgType, streamed);
        trackMetadata(metadata);
    }
}
