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

import java.util.ArrayList;
import java.util.List;

import javax.websocket.Encoder;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.eclipse.jetty.websocket.jsr356.MessageType;

public class EncoderMetadataSet extends CoderMetadataSet<Encoder, EncoderMetadata>
{
    @Override
    protected List<EncoderMetadata> discover(Class<? extends Encoder> encoder)
    {
        List<EncoderMetadata> metadatas = new ArrayList<>();

        if (Encoder.Binary.class.isAssignableFrom(encoder))
        {
            Class<?> objType = getEncoderType(encoder,Encoder.Binary.class);
            metadatas.add(new EncoderMetadata(encoder,objType,MessageType.BINARY,false));
        }
        if (Encoder.BinaryStream.class.isAssignableFrom(encoder))
        {
            Class<?> objType = getEncoderType(encoder,Encoder.BinaryStream.class);
            metadatas.add(new EncoderMetadata(encoder,objType,MessageType.BINARY,true));
        }
        if (Encoder.Text.class.isAssignableFrom(encoder))
        {
            Class<?> objType = getEncoderType(encoder,Encoder.Text.class);
            metadatas.add(new EncoderMetadata(encoder,objType,MessageType.TEXT,false));
        }
        if (Encoder.TextStream.class.isAssignableFrom(encoder))
        {
            Class<?> objType = getEncoderType(encoder,Encoder.TextStream.class);
            metadatas.add(new EncoderMetadata(encoder,objType,MessageType.TEXT,true));
        }

        if (!ReflectUtils.isDefaultConstructable(encoder))
        {
            throw new InvalidSignatureException("Encoder must have public, no-args constructor: " + encoder.getName());
        }

        if (metadatas.size() <= 0)
        {
            throw new InvalidSignatureException("Not a valid Encoder class: " + encoder.getName() + " implements no " + Encoder.class.getName() + " interfaces");
        }

        return metadatas;
    }

    private Class<?> getEncoderType(Class<? extends Encoder> encoder, Class<?> interfaceClass)
    {
        Class<?> decoderClass = ReflectUtils.findGenericClassFor(encoder,interfaceClass);
        if (decoderClass == null)
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid type declared for interface ");
            err.append(interfaceClass.getName());
            err.append(" on class ");
            err.append(encoder);
            throw new InvalidWebSocketException(err.toString());
        }
        return decoderClass;
    }

    protected final void register(Class<?> type, Class<? extends Encoder> encoder, MessageType msgType, boolean streamed)
    {
        EncoderMetadata metadata = new EncoderMetadata(encoder,type,msgType,streamed);
        trackMetadata(metadata);
    }
}
