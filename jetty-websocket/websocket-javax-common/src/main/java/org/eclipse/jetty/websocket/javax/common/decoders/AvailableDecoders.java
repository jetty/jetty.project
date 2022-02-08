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

package org.eclipse.jetty.websocket.javax.common.decoders;

import java.io.Closeable;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;

public class AvailableDecoders implements Iterable<RegisteredDecoder>, Closeable
{
    private final List<RegisteredDecoder> registeredDecoders = new ArrayList<>();
    private final EndpointConfig config;
    private final WebSocketComponents components;

    public AvailableDecoders(EndpointConfig config, WebSocketComponents components)
    {
        this.components = Objects.requireNonNull(components);

        // Register the Config Based Decoders.
        this.config = Objects.requireNonNull(config);
        registerAll(config.getDecoders());

        // TEXT based [via Class reference]
        registerPrimitive(BooleanDecoder.class, Decoder.Text.class, Boolean.class);
        registerPrimitive(ByteDecoder.class, Decoder.Text.class, Byte.class);
        registerPrimitive(CharacterDecoder.class, Decoder.Text.class, Character.class);
        registerPrimitive(DoubleDecoder.class, Decoder.Text.class, Double.class);
        registerPrimitive(FloatDecoder.class, Decoder.Text.class, Float.class);
        registerPrimitive(ShortDecoder.class, Decoder.Text.class, Short.class);
        registerPrimitive(IntegerDecoder.class, Decoder.Text.class, Integer.class);
        registerPrimitive(LongDecoder.class, Decoder.Text.class, Long.class);
        registerPrimitive(StringDecoder.class, Decoder.Text.class, String.class);

        // TEXT based [via Primitive reference]
        registerPrimitive(BooleanDecoder.class, Decoder.Text.class, Boolean.TYPE);
        registerPrimitive(ByteDecoder.class, Decoder.Text.class, Byte.TYPE);
        registerPrimitive(CharacterDecoder.class, Decoder.Text.class, Character.TYPE);
        registerPrimitive(DoubleDecoder.class, Decoder.Text.class, Double.TYPE);
        registerPrimitive(FloatDecoder.class, Decoder.Text.class, Float.TYPE);
        registerPrimitive(ShortDecoder.class, Decoder.Text.class, Short.TYPE);
        registerPrimitive(IntegerDecoder.class, Decoder.Text.class, Integer.TYPE);
        registerPrimitive(LongDecoder.class, Decoder.Text.class, Long.TYPE);

        // BINARY based
        registerPrimitive(ByteBufferDecoder.class, Decoder.Binary.class, ByteBuffer.class);
        registerPrimitive(ByteArrayDecoder.class, Decoder.Binary.class, byte[].class);

        // STREAMING based
        registerPrimitive(ReaderDecoder.class, Decoder.TextStream.class, Reader.class);
        registerPrimitive(InputStreamDecoder.class, Decoder.BinaryStream.class, InputStream.class);
    }

    private void registerPrimitive(Class<? extends Decoder> decoderClass, Class<? extends Decoder> interfaceType, Class<?> type)
    {
        registeredDecoders.add(new RegisteredDecoder(decoderClass, interfaceType, type, config, components, true));
    }

    private void register(Class<? extends Decoder> decoder)
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
            throw new InvalidSignatureException(
                "Not a valid Decoder class: " + decoder.getName() + " implements no " + Decoder.class.getName() + " interfaces");
        }
    }

    private void registerAll(List<Class<? extends Decoder>> decoders)
    {
        if (decoders == null)
            return;
        decoders.forEach(this::register);
    }

    private void add(Class<? extends Decoder> decoder, Class<? extends Decoder> interfaceClass)
    {
        Class<?> objectType = ReflectUtils.findGenericClassFor(decoder, interfaceClass);
        if (objectType == null)
        {
            String err = "Unknown Decoder Object type declared for interface " +
                interfaceClass.getName() + " on class " + decoder;
            throw new InvalidWebSocketException(err);
        }

        // Validate the decoder to be added against the existing registered decoders.
        for (RegisteredDecoder registered : registeredDecoders)
        {
            if (!registered.primitive && objectType.equals(registered.objectType))
            {
                // Streaming decoders can only have one decoder per object type.
                if (interfaceClass.equals(Decoder.TextStream.class) || interfaceClass.equals(Decoder.BinaryStream.class))
                    throw new InvalidWebSocketException("Multiple decoders for objectType" + objectType);

                // If we have the same objectType, then the interfaceTypes must be the same to form a decoder list.
                if (!registered.interfaceType.equals(interfaceClass))
                    throw new InvalidWebSocketException("Multiple decoders with different interface types for objectType " + objectType);
            }

            // If this decoder is already registered for this interface type we can skip adding a duplicate.
            if (registered.decoder.equals(decoder) && registered.interfaceType.equals(interfaceClass))
                return;
        }

        registeredDecoders.add(new RegisteredDecoder(decoder, interfaceClass, objectType, config, components));
    }

    public RegisteredDecoder getFirstRegisteredDecoder(Class<?> type)
    {
        return registeredDecoders.stream()
            .filter(registered -> registered.isType(type))
            .findFirst()
            .orElse(null);
    }

    public List<RegisteredDecoder> getRegisteredDecoders(Class<?> returnType)
    {
        return registeredDecoders.stream()
            .filter(registered -> registered.isType(returnType))
            .collect(Collectors.toList());
    }

    public List<RegisteredDecoder> getRegisteredDecoders(Class<? extends Decoder> interfaceType, Class<?> returnType)
    {
        return registeredDecoders.stream()
            .filter(registered -> registered.interfaceType.equals(interfaceType))
            .filter(registered -> registered.isType(returnType))
            .collect(Collectors.toList());
    }

    public List<RegisteredDecoder> getTextDecoders(Class<?> returnType)
    {
        return getRegisteredDecoders(Decoder.Text.class, returnType);
    }

    public List<RegisteredDecoder> getBinaryDecoders(Class<?> returnType)
    {
        return getRegisteredDecoders(Decoder.Binary.class, returnType);
    }

    public List<RegisteredDecoder> getTextStreamDecoders(Class<?> returnType)
    {
        return getRegisteredDecoders(Decoder.TextStream.class, returnType);
    }

    public List<RegisteredDecoder> getBinaryStreamDecoders(Class<?> returnType)
    {
        return getRegisteredDecoders(Decoder.BinaryStream.class, returnType);
    }

    @Override
    public Iterator<RegisteredDecoder> iterator()
    {
        return registeredDecoders.iterator();
    }

    public Stream<RegisteredDecoder> stream()
    {
        return registeredDecoders.stream();
    }

    @Override
    public void close()
    {
        registeredDecoders.forEach(RegisteredDecoder::destroyInstance);
    }
}
