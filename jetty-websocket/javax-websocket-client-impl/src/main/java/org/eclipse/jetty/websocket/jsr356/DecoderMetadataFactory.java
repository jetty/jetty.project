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

import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.Decoder;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
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
import org.eclipse.jetty.websocket.jsr356.utils.DeploymentTypeUtils;

/**
 * Global Factory for all declared Decoders in all endpoints.
 */
public class DecoderMetadataFactory
{
    public static class DefaultsDecoderFactory extends DecoderMetadataFactory
    {
        public static final DefaultsDecoderFactory INSTANCE = new DefaultsDecoderFactory();

        private Map<Type, Class<? extends Decoder>> typeMap = new HashMap<>();

        public DefaultsDecoderFactory()
        {
            boolean streamed = false;
            // TEXT based - Classes
            MessageType msgType = MessageType.TEXT;
            register(Boolean.class,BooleanDecoder.class,msgType,streamed);
            register(Byte.class,ByteDecoder.class,msgType,streamed);
            register(Character.class,CharacterDecoder.class,msgType,streamed);
            register(Double.class,DoubleDecoder.class,msgType,streamed);
            register(Float.class,FloatDecoder.class,msgType,streamed);
            register(Integer.class,IntegerDecoder.class,msgType,streamed);
            register(Long.class,LongDecoder.class,msgType,streamed);
            register(Short.class,ShortDecoder.class,msgType,streamed);
            register(String.class,StringDecoder.class,msgType,streamed);

            // TEXT based - Primitives
            msgType = MessageType.TEXT;
            register(Boolean.TYPE,BooleanDecoder.class,msgType,streamed);
            register(Byte.TYPE,ByteDecoder.class,msgType,streamed);
            register(Character.TYPE,CharacterDecoder.class,msgType,streamed);
            register(Double.TYPE,DoubleDecoder.class,msgType,streamed);
            register(Float.TYPE,FloatDecoder.class,msgType,streamed);
            register(Integer.TYPE,IntegerDecoder.class,msgType,streamed);
            register(Long.TYPE,LongDecoder.class,msgType,streamed);
            register(Short.TYPE,ShortDecoder.class,msgType,streamed);

            // BINARY based
            msgType = MessageType.BINARY;
            register(ByteBuffer.class,ByteBufferDecoder.class,msgType,streamed);
            register(byte[].class,ByteArrayDecoder.class,msgType,streamed);
        }

        public Class<? extends Decoder> getDecoder(Class<?> type)
        {
            return typeMap.get(type);
        }

        private void register(Class<?> typeClass, Class<? extends Decoder> decoderClass, MessageType msgType, boolean streamed)
        {
            List<DecoderMetadata> metadatas = new ArrayList<>();
            metadatas.add(new DecoderMetadata(typeClass,decoderClass,msgType,streamed));
            cache.put(decoderClass,metadatas);
            typeMap.put(typeClass,decoderClass);
        }

    }

    private static final Logger LOG = Log.getLogger(DecoderMetadataFactory.class);
    protected Map<Class<? extends Decoder>, List<DecoderMetadata>> cache = new ConcurrentHashMap<>();

    private Class<?> getDecoderMessageClass(Class<? extends Decoder> decoder, Class<?> interfaceClass)
    {
        Type genericType = DeploymentTypeUtils.getGenericType(decoder,interfaceClass);
        if (genericType instanceof Class<?>)
        {
            return (Class<?>)genericType;
        }
        StringBuilder err = new StringBuilder();
        err.append("Invalid type declared for interface ");
        err.append(interfaceClass.getName());
        err.append(" on class ");
        err.append(decoder);
        throw new IllegalArgumentException(err.toString());
    }

    public List<DecoderMetadata> getMetadata(Class<? extends Decoder> decoder)
    {
        LOG.debug("getDecoder({})",decoder);
        List<DecoderMetadata> ret = cache.get(decoder);

        if (ret == null)
        {
            ret = new ArrayList<>();

            if (Decoder.Binary.class.isAssignableFrom(decoder))
            {
                Class<?> objType = getDecoderMessageClass(decoder,Decoder.Binary.class);
                ret.add(new DecoderMetadata(objType,decoder,MessageType.BINARY,false));
            }
            if (Decoder.BinaryStream.class.isAssignableFrom(decoder))
            {
                Class<?> objType = getDecoderMessageClass(decoder,Decoder.BinaryStream.class);
                ret.add(new DecoderMetadata(objType,decoder,MessageType.BINARY,true));
            }
            if (Decoder.Text.class.isAssignableFrom(decoder))
            {
                Class<?> objType = getDecoderMessageClass(decoder,Decoder.Text.class);
                ret.add(new DecoderMetadata(objType,decoder,MessageType.TEXT,false));
            }
            if (Decoder.TextStream.class.isAssignableFrom(decoder))
            {
                Class<?> objType = getDecoderMessageClass(decoder,Decoder.TextStream.class);
                ret.add(new DecoderMetadata(objType,decoder,MessageType.TEXT,true));
            }

            if (ret.size() <= 0)
            {
                throw new InvalidSignatureException("Not a valid Decoder class: " + decoder.getName());
            }

            LOG.debug("New Hit [{} entries]",ret.size());
            cache.put(decoder,ret);
        }
        else
        {
            LOG.debug("From Cache");
        }

        return ret;
    }
}
