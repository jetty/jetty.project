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

package org.eclipse.jetty.websocket.jsr356.decoders;

import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import javax.websocket.PongMessage;

import org.eclipse.jetty.websocket.jsr356.MessageType;
import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadataSet;

public class PrimitiveDecoderMetadataSet extends DecoderMetadataSet
{
    public static final DecoderMetadataSet INSTANCE = new PrimitiveDecoderMetadataSet();

    public PrimitiveDecoderMetadataSet()
    {
        boolean streamed = false;
        // TEXT based - Classes Based
        MessageType msgType = MessageType.TEXT;
        register(Boolean.class, BooleanDecoder.class, msgType, streamed);
        register(Byte.class, ByteDecoder.class, msgType, streamed);
        register(Character.class, CharacterDecoder.class, msgType, streamed);
        register(Double.class, DoubleDecoder.class, msgType, streamed);
        register(Float.class, FloatDecoder.class, msgType, streamed);
        register(Integer.class, IntegerDecoder.class, msgType, streamed);
        register(Long.class, LongDecoder.class, msgType, streamed);
        register(Short.class, ShortDecoder.class, msgType, streamed);
        register(String.class, StringDecoder.class, msgType, streamed);

        // TEXT based - Primitive Types
        msgType = MessageType.TEXT;
        register(Boolean.TYPE, BooleanDecoder.class, msgType, streamed);
        register(Byte.TYPE, ByteDecoder.class, msgType, streamed);
        register(Character.TYPE, CharacterDecoder.class, msgType, streamed);
        register(Double.TYPE, DoubleDecoder.class, msgType, streamed);
        register(Float.TYPE, FloatDecoder.class, msgType, streamed);
        register(Integer.TYPE, IntegerDecoder.class, msgType, streamed);
        register(Long.TYPE, LongDecoder.class, msgType, streamed);
        register(Short.TYPE, ShortDecoder.class, msgType, streamed);

        // BINARY based
        msgType = MessageType.BINARY;
        register(ByteBuffer.class, ByteBufferDecoder.class, msgType, streamed);
        register(byte[].class, ByteArrayDecoder.class, msgType, streamed);

        // PONG based
        msgType = MessageType.PONG;
        register(PongMessage.class, PongMessageDecoder.class, msgType, streamed);

        // STREAMING based
        streamed = true;
        msgType = MessageType.TEXT;
        register(Reader.class, ReaderDecoder.class, msgType, streamed);
        msgType = MessageType.BINARY;
        register(InputStream.class, InputStreamDecoder.class, msgType, streamed);
    }
}
