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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.websocket.Encoder;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

public class AvailableEncoders implements Predicate<Class<?>>
{
    private static class RegisteredEncoder implements Predicate<Class<?>>
    {
        public final Class<? extends Encoder> encoder;
        public final Class<?> objectType;

        public RegisteredEncoder(Class<? extends Encoder> encoder, Class<?> objectType)
        {
            this.encoder = encoder;
            this.objectType = objectType;
        }

        @Override
        public boolean test(Class<?> type)
        {
            return objectType.isAssignableFrom(type);
        }
    }

    private List<RegisteredEncoder> registeredEncoders;

    public void register(Class<? extends Encoder> encoder)
    {
        if (!ReflectUtils.isDefaultConstructable(encoder))
        {
            throw new InvalidSignatureException("Encoder must have public, no-args constructor: " + encoder.getName());
        }

        boolean foundEncoder = false;

        if (Encoder.Binary.class.isAssignableFrom(encoder))
        {
            add(encoder, Encoder.Binary.class);
            foundEncoder = true;
        }

        if (Encoder.BinaryStream.class.isAssignableFrom(encoder))
        {
            add(encoder, Encoder.BinaryStream.class);
            foundEncoder = true;
        }

        if (Encoder.Text.class.isAssignableFrom(encoder))
        {
            add(encoder, Encoder.Text.class);
            foundEncoder = true;
        }

        if (Encoder.TextStream.class.isAssignableFrom(encoder))
        {
            add(encoder, Encoder.TextStream.class);
            foundEncoder = true;
        }

        if (!foundEncoder)
        {
            throw new InvalidSignatureException("Not a valid Encoder class: " + encoder.getName() + " implements no " + Encoder.class.getName() + " interfaces");
        }
    }

    public void registerAll(Class<? extends Encoder>[] encoders)
    {
        if (encoders == null)
            return;

        for (Class<? extends Encoder> encoder : encoders)
        {
            register(encoder);
        }
    }

    public void registerAll(List<Class<? extends Encoder>> encoders)
    {
        if (encoders == null)
            return;

        encoders.forEach(this::register);
    }

    private void add(Class<? extends Encoder> encoder, Class<?> interfaceClass)
    {
        Class<?> objectType = ReflectUtils.findGenericClassFor(encoder, interfaceClass);
        if (objectType == null)
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid Encoder Object type declared for interface ");
            err.append(interfaceClass.getName());
            err.append(" on class ");
            err.append(encoder);
            throw new InvalidWebSocketException(err.toString());
        }

        if (registeredEncoders == null)
            registeredEncoders = new ArrayList<>();

        registeredEncoders.add(new RegisteredEncoder(encoder, objectType));
    }

    public Class<? extends Encoder> getEncoderFor(Class<?> type)
    {
        // Check registered encoders first
        if (registeredEncoders != null)
        {
            for (RegisteredEncoder registered : registeredEncoders)
            {
                if (registered.objectType.isAssignableFrom(type))
                    return registered.encoder;
            }
        }

        // Check default encoders next

        // TEXT based [via Class reference]
        if (Boolean.class.isAssignableFrom(type)) return BooleanEncoder.class;
        if (Byte.class.isAssignableFrom(type)) return ByteEncoder.class;
        if (Character.class.isAssignableFrom(type)) return CharacterEncoder.class;
        if (Double.class.isAssignableFrom(type)) return DoubleEncoder.class;
        if (Float.class.isAssignableFrom(type)) return FloatEncoder.class;
        if (Integer.class.isAssignableFrom(type)) return IntegerEncoder.class;
        if (Long.class.isAssignableFrom(type)) return LongEncoder.class;
        if (String.class.isAssignableFrom(type)) return StringEncoder.class;

        // TEXT based [via Primitive reference]
        if (Boolean.TYPE.isAssignableFrom(type)) return BooleanEncoder.class;
        if (Byte.TYPE.isAssignableFrom(type)) return ByteEncoder.class;
        if (Character.TYPE.isAssignableFrom(type)) return CharacterEncoder.class;
        if (Double.TYPE.isAssignableFrom(type)) return DoubleEncoder.class;
        if (Float.TYPE.isAssignableFrom(type)) return FloatEncoder.class;
        if (Integer.TYPE.isAssignableFrom(type)) return IntegerEncoder.class;
        if (Long.TYPE.isAssignableFrom(type)) return LongEncoder.class;

        // BINARY based
        if (ByteBuffer.class.isAssignableFrom(type)) return ByteBufferEncoder.class;
        if (byte[].class.isAssignableFrom(type)) return ByteArrayEncoder.class;

        // Note: Streams (Writer / OutputStream) are not present here
        // as you don't write with a Stream via an encoder, you tell the
        // encoder to write an object to a Stream

        throw new InvalidWebSocketException("No Encoder found for type " + type);
    }

    @Override
    public boolean test(Class<?> type)
    {
        if (registeredEncoders != null)
        {
            for (RegisteredEncoder registered : registeredEncoders)
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
                String.class.isAssignableFrom(type))
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
                byte[].class.isAssignableFrom(type))
        {
            return true;
        }

        return false;
    }
}
