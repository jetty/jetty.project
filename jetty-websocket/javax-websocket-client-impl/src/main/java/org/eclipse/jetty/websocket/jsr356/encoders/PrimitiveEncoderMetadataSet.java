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

package org.eclipse.jetty.websocket.jsr356.encoders;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.jsr356.MessageType;
import org.eclipse.jetty.websocket.jsr356.metadata.EncoderMetadataSet;

public class PrimitiveEncoderMetadataSet extends EncoderMetadataSet
{
    public static final EncoderMetadataSet INSTANCE = new PrimitiveEncoderMetadataSet();

    public PrimitiveEncoderMetadataSet()
    {
        boolean streamed = false;
        // TEXT based - Classes Based
        MessageType msgType = MessageType.TEXT;
        register(Boolean.class,BooleanEncoder.class,msgType,streamed);
        register(Byte.class,ByteEncoder.class,msgType,streamed);
        register(Character.class,CharacterEncoder.class,msgType,streamed);
        register(Double.class,DoubleEncoder.class,msgType,streamed);
        register(Float.class,FloatEncoder.class,msgType,streamed);
        register(Integer.class,IntegerEncoder.class,msgType,streamed);
        register(Long.class,LongEncoder.class,msgType,streamed);
        register(Short.class,ShortEncoder.class,msgType,streamed);
        register(String.class,StringEncoder.class,msgType,streamed);

        // TEXT based - Primitive Types
        msgType = MessageType.TEXT;
        register(Boolean.TYPE,BooleanEncoder.class,msgType,streamed);
        register(Byte.TYPE,ByteEncoder.class,msgType,streamed);
        register(Character.TYPE,CharacterEncoder.class,msgType,streamed);
        register(Double.TYPE,DoubleEncoder.class,msgType,streamed);
        register(Float.TYPE,FloatEncoder.class,msgType,streamed);
        register(Integer.TYPE,IntegerEncoder.class,msgType,streamed);
        register(Long.TYPE,LongEncoder.class,msgType,streamed);
        register(Short.TYPE,ShortEncoder.class,msgType,streamed);

        // BINARY based
        msgType = MessageType.BINARY;
        register(ByteBuffer.class,ByteBufferEncoder.class,msgType,streamed);
        register(byte[].class,ByteArrayEncoder.class,msgType,streamed);

    }
}
