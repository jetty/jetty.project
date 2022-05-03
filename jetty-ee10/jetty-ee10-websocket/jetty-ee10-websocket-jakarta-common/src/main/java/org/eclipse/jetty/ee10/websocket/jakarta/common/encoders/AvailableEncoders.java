//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.jakarta.common.encoders;

import java.io.Closeable;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;
import org.eclipse.jetty.ee10.websocket.jakarta.common.InitException;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AvailableEncoders implements Predicate<Class<?>>, Closeable
{
    private static final Logger LOG = LoggerFactory.getLogger(AvailableEncoders.class);

    private final EndpointConfig config;
    private final WebSocketComponents components;
    private final LinkedList<RegisteredEncoder> registeredEncoders;

    public AvailableEncoders(EndpointConfig config, WebSocketComponents components)
    {
        this.config = Objects.requireNonNull(config);
        this.components = Objects.requireNonNull(components);
        this.registeredEncoders = new LinkedList<>();

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

            registeredEncoder.instance = components.getObjectFactory().createInstance(registeredEncoder.encoder);
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

    @Override
    public void close()
    {
        registeredEncoders.forEach(RegisteredEncoder::destroyInstance);
    }
}
