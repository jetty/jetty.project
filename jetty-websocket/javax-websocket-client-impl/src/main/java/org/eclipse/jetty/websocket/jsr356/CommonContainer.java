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

import java.nio.ByteBuffer;
import java.util.Set;

import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.websocket.jsr356.decoders.BooleanDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.ByteArrayDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.ByteBufferDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.ByteDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.CharacterDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.DoubleDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.FloatDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.IntegerDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.LongDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.ShortDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.StringDecoder;

/**
 * Service Interface for working with Jetty Internal Container.
 */
public abstract class CommonContainer implements WebSocketContainer
{
    /** Tracking all primitive decoders for the container */
    private final DecoderFactory decoderFactory;
    /** Tracking all primitive encoders for the container */
    private final EncoderFactory encoderFactory;

    public CommonContainer()
    {
        decoderFactory = new DecoderFactory();
        encoderFactory = new EncoderFactory();

        // ---------------------------------------
        // Register Decoder Primitives
        // ---------------------------------------

        boolean streamed = false;
        // TEXT based - Classes Based
        MessageType msgType = MessageType.TEXT;
        decoderFactory.register(Boolean.class,BooleanDecoder.class,msgType,streamed);
        decoderFactory.register(Byte.class,ByteDecoder.class,msgType,streamed);
        decoderFactory.register(Character.class,CharacterDecoder.class,msgType,streamed);
        decoderFactory.register(Double.class,DoubleDecoder.class,msgType,streamed);
        decoderFactory.register(Float.class,FloatDecoder.class,msgType,streamed);
        decoderFactory.register(Integer.class,IntegerDecoder.class,msgType,streamed);
        decoderFactory.register(Long.class,LongDecoder.class,msgType,streamed);
        decoderFactory.register(Short.class,ShortDecoder.class,msgType,streamed);
        decoderFactory.register(String.class,StringDecoder.class,msgType,streamed);

        // TEXT based - Primitive Types
        msgType = MessageType.TEXT;
        decoderFactory.register(Boolean.TYPE,BooleanDecoder.class,msgType,streamed);
        decoderFactory.register(Byte.TYPE,ByteDecoder.class,msgType,streamed);
        decoderFactory.register(Character.TYPE,CharacterDecoder.class,msgType,streamed);
        decoderFactory.register(Double.TYPE,DoubleDecoder.class,msgType,streamed);
        decoderFactory.register(Float.TYPE,FloatDecoder.class,msgType,streamed);
        decoderFactory.register(Integer.TYPE,IntegerDecoder.class,msgType,streamed);
        decoderFactory.register(Long.TYPE,LongDecoder.class,msgType,streamed);
        decoderFactory.register(Short.TYPE,ShortDecoder.class,msgType,streamed);

        // BINARY based
        msgType = MessageType.BINARY;
        decoderFactory.register(ByteBuffer.class,ByteBufferDecoder.class,msgType,streamed);
        decoderFactory.register(byte[].class,ByteArrayDecoder.class,msgType,streamed);
    }

    public DecoderFactory getDecoderFactory()
    {
        return decoderFactory;
    }

    public EncoderFactory getEncoderFactory()
    {
        return encoderFactory;
    }

    /**
     * Get set of open sessions.
     * 
     * @return the set of open sessions
     */
    public abstract Set<Session> getOpenSessions();

    /**
     * Start the container
     */
    public abstract void start();

    /**
     * Stop the container
     */
    public abstract void stop();
}
