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

import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.PongMessage;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

public class AvailableDecoders implements Predicate<Class<?>>
{
    private static class RegisteredDecoder implements Predicate<Class<?>>
    {
        public final Class<? extends Decoder> decoder;
        public final Class<?> objectType;

        public RegisteredDecoder(Class<? extends Decoder> decoder, Class<?> objectType)
        {
            this.decoder = decoder;
            this.objectType = objectType;
        }

        @Override
        public boolean test(Class<?> type)
        {
            return objectType.isAssignableFrom(type);
        }
    }

    private List<RegisteredDecoder> registeredDecoders;

    public void register(Class<? extends Decoder> decoder)
    {
        if (!ReflectUtils.isDefaultConstructable(decoder))
        {
            throw new InvalidSignatureException("Decoder must have public, no-args constructor: " + decoder.getName());
        }

        boolean foundDecoder = false;

        if (Decoder.Binary.class.isAssignableFrom(decoder))
        {
            add(decoder, Decoder.Binary.class);
            foundDecoder = true;
        }

        if (Decoder.BinaryStream.class.isAssignableFrom(decoder))
        {
            add(decoder, Decoder.BinaryStream.class);
            foundDecoder = true;
        }

        if (Decoder.Text.class.isAssignableFrom(decoder))
        {
            add(decoder, Decoder.Text.class);
            foundDecoder = true;
        }

        if (Decoder.TextStream.class.isAssignableFrom(decoder))
        {
            add(decoder, Decoder.TextStream.class);
            foundDecoder = true;
        }

        if (!foundDecoder)
        {
            throw new InvalidSignatureException("Not a valid Decoder class: " + decoder.getName() + " implements no " + Decoder.class.getName() + " interfaces");
        }
    }

    public void registerAll(Class<? extends Decoder>[] decoders)
    {
        if (decoders == null)
            return;

        for (Class<? extends Decoder> decoder : decoders)
        {
            register(decoder);
        }
    }

    public void registerAll(List<Class<? extends Decoder>> decoders)
    {
        if (decoders == null)
            return;

        decoders.forEach(this::register);
    }

    private void add(Class<? extends Decoder> decoder, Class<?> interfaceClass)
    {
        Class<?> objectType = ReflectUtils.findGenericClassFor(decoder, interfaceClass);
        if (objectType == null)
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid Decoder Object type declared for interface ");
            err.append(interfaceClass.getName());
            err.append(" on class ");
            err.append(decoder);
            throw new InvalidWebSocketException(err.toString());
        }

        if (registeredDecoders == null)
            registeredDecoders = new ArrayList<>();

        registeredDecoders.add(new RegisteredDecoder(decoder, objectType));
    }

    public Class<? extends Decoder> getDecoderFor(Class<?> type)
    {
        // Check registered decoders first
        if (registeredDecoders != null)
        {
            for (RegisteredDecoder registered : registeredDecoders)
            {
                if (registered.objectType.isAssignableFrom(type))
                    return registered.decoder;
            }
        }

        // Check default decoders next

        // TEXT based [via Class reference]
        if (Boolean.class.isAssignableFrom(type)) return BooleanDecoder.class;
        if (Byte.class.isAssignableFrom(type)) return ByteDecoder.class;
        if (Character.class.isAssignableFrom(type)) return CharacterDecoder.class;
        if (Double.class.isAssignableFrom(type)) return DoubleDecoder.class;
        if (Float.class.isAssignableFrom(type)) return FloatDecoder.class;
        if (Integer.class.isAssignableFrom(type)) return IntegerDecoder.class;
        if (Long.class.isAssignableFrom(type)) return LongDecoder.class;
        if (String.class.isAssignableFrom(type)) return StringDecoder.class;

        // TEXT based [via Primitive reference]
        if (Boolean.TYPE.isAssignableFrom(type)) return BooleanDecoder.class;
        if (Byte.TYPE.isAssignableFrom(type)) return ByteDecoder.class;
        if (Character.TYPE.isAssignableFrom(type)) return CharacterDecoder.class;
        if (Double.TYPE.isAssignableFrom(type)) return DoubleDecoder.class;
        if (Float.TYPE.isAssignableFrom(type)) return FloatDecoder.class;
        if (Integer.TYPE.isAssignableFrom(type)) return IntegerDecoder.class;
        if (Long.TYPE.isAssignableFrom(type)) return LongDecoder.class;

        // BINARY based
        if (ByteBuffer.class.isAssignableFrom(type)) return ByteBufferDecoder.class;
        if (byte[].class.isAssignableFrom(type)) return ByteArrayDecoder.class;

        // PONG based
        if (PongMessage.class.isAssignableFrom(type)) return PongMessageDecoder.class;

        // STREAMING based
        if (Reader.class.isAssignableFrom(type)) return ReaderDecoder.class;
        if (InputStream.class.isAssignableFrom(type)) return InputStreamDecoder.class;

        throw new InvalidWebSocketException("No Decoder found for type " + type);
    }

    public static Object decodePrimitive(String value, Class<?> type) throws DecodeException
    {
        if (value == null)
            return null;

        // Simplest (and most common) form of @PathParam
        if (String.class.isAssignableFrom(type))
            return value;

        try
        {
            // Per JSR356 spec, just the java primitives
            if (Boolean.class.isAssignableFrom(type))
            {
                return new Boolean(value);
            }
            if (Boolean.TYPE.isAssignableFrom(type))
            {
                return Boolean.parseBoolean(value);
            }
            if (Byte.class.isAssignableFrom(type))
            {
                return new Byte(value);
            }
            if (Byte.TYPE.isAssignableFrom(type))
            {
                return Byte.parseByte(value);
            }
            if (Character.class.isAssignableFrom(type))
            {
                if (value.length() != 1)
                    throw new DecodeException(value, "Invalid Size: Cannot decode as type " + Character.class.getName());
                return new Character(value.charAt(0));
            }
            if (Character.TYPE.isAssignableFrom(type))
            {
                if (value.length() != 1)
                    throw new DecodeException(value, "Invalid Size: Cannot decode as type " + Character.class.getName());
                return value.charAt(0);
            }
            if (Double.class.isAssignableFrom(type))
            {
                return new Double(value);
            }
            if (Double.TYPE.isAssignableFrom(type))
            {
                return Double.parseDouble(value);
            }
            if (Float.class.isAssignableFrom(type))
            {
                return new Float(value);
            }
            if (Float.TYPE.isAssignableFrom(type))
            {
                return Float.parseFloat(value);
            }
            if (Integer.class.isAssignableFrom(type))
            {
                return new Integer(value);
            }
            if (Integer.TYPE.isAssignableFrom(type))
            {
                return Integer.parseInt(value);
            }
            if (Long.class.isAssignableFrom(type))
            {
                return new Long(value);
            }
            if (Long.TYPE.isAssignableFrom(type))
            {
                return Long.parseLong(value);
            }

            // Not a primitive!
            throw new DecodeException(value, "Not a recognized primitive type: " + type);
        }
        catch (NumberFormatException e)
        {
            throw new DecodeException(value, "Unable to decode as type " + type.getName());
        }
    }

    @Override
    public boolean test(Class<?> type)
    {
        if (registeredDecoders != null)
        {
            for (RegisteredDecoder registered : registeredDecoders)
            {
                if (registered.test(type))
                    return true;
            }
        }

        // TEXT based [via Class references]
        if (Boolean.class.isAssignableFrom(type) ||
                Byte.class.isAssignableFrom(type) ||
                Character.class.isAssignableFrom(type) ||
                Double.class.isAssignableFrom(type) ||
                Float.class.isAssignableFrom(type) ||
                Integer.class.isAssignableFrom(type) ||
                Long.class.isAssignableFrom(type) ||
                String.class.isAssignableFrom(type) ||
                Reader.class.isAssignableFrom(type))
        {
            return true;
        }

        // TEXT based [via Primitive reference]
        if (Boolean.TYPE.isAssignableFrom(type) ||
                Byte.TYPE.isAssignableFrom(type) ||
                Character.TYPE.isAssignableFrom(type) ||
                Double.TYPE.isAssignableFrom(type) ||
                Float.TYPE.isAssignableFrom(type) ||
                Integer.TYPE.isAssignableFrom(type) ||
                Long.TYPE.isAssignableFrom(type))
        {
            return true;
        }

        // BINARY based
        if (ByteBuffer.class.isAssignableFrom(type) ||
                byte[].class.isAssignableFrom(type) ||
                InputStream.class.isAssignableFrom(type))
        {
            return true;
        }

        // PONG based
        if (PongMessage.class.isAssignableFrom(type))
        {
            return true;
        }

        return false;
    }
}
