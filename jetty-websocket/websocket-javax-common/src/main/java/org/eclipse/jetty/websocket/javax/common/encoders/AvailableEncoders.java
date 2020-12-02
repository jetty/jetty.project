//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.common.encoders;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;
import org.eclipse.jetty.websocket.javax.common.InitException;

public class AvailableEncoders implements Predicate<Class<?>>
{
    public static class RegisteredEncoder
    {
        public final Class<? extends Encoder> encoder;
        public final Class<? extends Encoder> interfaceType;
        public final Class<?> objectType;
        public final boolean primitive;
        public Encoder instance;

        public RegisteredEncoder(Class<? extends Encoder> encoder, Class<? extends Encoder> interfaceType, Class<?> objectType)
        {
            this(encoder, interfaceType, objectType, false);
        }

        public RegisteredEncoder(Class<? extends Encoder> encoder, Class<? extends Encoder> interfaceType, Class<?> objectType, boolean primitive)
        {
            this.encoder = encoder;
            this.interfaceType = interfaceType;
            this.objectType = objectType;
            this.primitive = primitive;
        }

        public boolean implementsInterface(Class<? extends Encoder> type)
        {
            return interfaceType.isAssignableFrom(type);
        }

        public boolean isType(Class<?> type)
        {
            return objectType.isAssignableFrom(type);
        }

        @Override
        public String toString()
        {
            StringBuilder str = new StringBuilder();
            str.append(AvailableEncoders.RegisteredEncoder.class.getSimpleName());
            str.append('[').append(encoder.getName());
            str.append(',').append(interfaceType.getName());
            str.append(',').append(objectType.getName());
            if (primitive)
            {
                str.append(",PRIMITIVE");
            }
            str.append(']');
            return str.toString();
        }
    }

    private final EndpointConfig config;
    private LinkedList<RegisteredEncoder> registeredEncoders;

    public AvailableEncoders(EndpointConfig config)
    {
        Objects.requireNonNull(config);
        this.config = config;
        registeredEncoders = new LinkedList<>();

        // TEXT based [via Class reference]
        registerPrimitive(BooleanEncoder.class, Encoder.Text.class, Boolean.class);
        registerPrimitive(ByteEncoder.class, Encoder.Text.class, Byte.class);
        registerPrimitive(CharacterEncoder.class, Encoder.Text.class, Character.class);
        registerPrimitive(DoubleEncoder.class, Encoder.Text.class, Double.class);
        registerPrimitive(FloatEncoder.class, Encoder.Text.class, Float.class);
        registerPrimitive(ShortEncoder.class, Encoder.Text.class, Short.class);
        registerPrimitive(IntegerEncoder.class, Encoder.Text.class, Integer.class);
        registerPrimitive(LongEncoder.class, Encoder.Text.class, Long.class);
        registerPrimitive(StringEncoder.class, Encoder.Text.class, String.class);

        // TEXT based [via Primitive reference]
        registerPrimitive(BooleanEncoder.class, Encoder.Text.class, Boolean.TYPE);
        registerPrimitive(ByteEncoder.class, Encoder.Text.class, Byte.TYPE);
        registerPrimitive(CharacterEncoder.class, Encoder.Text.class, Character.TYPE);
        registerPrimitive(DoubleEncoder.class, Encoder.Text.class, Double.TYPE);
        registerPrimitive(FloatEncoder.class, Encoder.Text.class, Float.TYPE);
        registerPrimitive(ShortEncoder.class, Encoder.Text.class, Short.TYPE);
        registerPrimitive(IntegerEncoder.class, Encoder.Text.class, Integer.TYPE);
        registerPrimitive(LongEncoder.class, Encoder.Text.class, Long.TYPE);

        // BINARY based
        registerPrimitive(ByteBufferEncoder.class, Encoder.Binary.class, ByteBuffer.class);
        registerPrimitive(ByteArrayEncoder.class, Encoder.Binary.class, byte[].class);

        // STREAMING based
        // Note: Streams (Writer / OutputStream) are not present here
        // as you don't write with a Stream via an encoder, you tell the
        // encoder to write an object to a Stream
        // register(WriterEncoder.class, Encoder.TextStream.class, Writer.class);
        // register(OutputStreamEncoder.class, Encoder.BinaryStream.class, OutputStream.class);

        // Config Based
        registerAll(config.getEncoders());
    }

    private void registerPrimitive(Class<? extends Encoder> encoderClass, Class<? extends Encoder> interfaceType, Class<?> type)
    {
        registeredEncoders.add(new RegisteredEncoder(encoderClass, interfaceType, type, true));
    }

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
            throw new InvalidSignatureException(
                "Not a valid Encoder class: " + encoder.getName() + " implements no " + Encoder.class.getName() + " interfaces");
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

    private void add(Class<? extends Encoder> encoder, Class<? extends Encoder> interfaceClass)
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

        try
        {
            RegisteredEncoder conflicts = registeredEncoders.stream()
                .filter(registered -> registered.isType(objectType))
                .filter(registered -> !registered.primitive)
                .findFirst()
                .get();

            if (conflicts.encoder.equals(encoder) && conflicts.implementsInterface(interfaceClass))
            {
                // Same encoder as what is there already, don't bother adding it again.
                return;
            }

            StringBuilder err = new StringBuilder();
            err.append("Duplicate Encoder Object type ");
            err.append(objectType.getName());
            err.append(" in ");
            err.append(encoder.getName());
            err.append(", previously declared in ");
            err.append(conflicts.encoder.getName());
            throw new InvalidWebSocketException(err.toString());
        }
        catch (NoSuchElementException e)
        {
            registeredEncoders.addFirst(new RegisteredEncoder(encoder, interfaceClass, objectType));
        }
    }

    public List<RegisteredEncoder> supporting(Class<? extends Encoder> interfaceType)
    {
        return registeredEncoders.stream()
            .filter(registered -> registered.implementsInterface(interfaceType))
            .collect(Collectors.toList());
    }

    public RegisteredEncoder getRegisteredEncoderFor(Class<?> type)
    {
        return registeredEncoders.stream()
            .filter(registered -> registered.isType(type))
            .findFirst()
            .get();
    }

    public Class<? extends Encoder> getEncoderFor(Class<?> type)
    {
        try
        {
            return getRegisteredEncoderFor(type).encoder;
        }
        catch (NoSuchElementException e)
        {
            throw new InvalidWebSocketException("No Encoder found for type " + type);
        }
    }

    public Encoder getInstanceFor(Class<?> type)
    {
        try
        {
            RegisteredEncoder registeredEncoder = getRegisteredEncoderFor(type);
            if (registeredEncoder.instance != null)
            {
                return registeredEncoder.instance;
            }

            registeredEncoder.instance = registeredEncoder.encoder.getConstructor().newInstance();
            registeredEncoder.instance.init(this.config);
            return registeredEncoder.instance;
        }
        catch (NoSuchElementException e)
        {
            throw new InvalidWebSocketException("No Encoder found for type " + type);
        }
        catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e)
        {
            throw new InitException("Unable to init Encoder for type:" + type.getName(), e);
        }
    }

    @Override
    public boolean test(Class<?> type)
    {
        return registeredEncoders.stream().anyMatch(registered -> registered.isType(type));
    }
}
