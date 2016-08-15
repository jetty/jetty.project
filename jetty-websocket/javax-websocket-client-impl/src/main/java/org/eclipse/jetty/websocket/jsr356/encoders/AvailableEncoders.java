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
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.eclipse.jetty.websocket.jsr356.InitException;

public class AvailableEncoders implements Predicate<Class<?>>
{
    public static class RegisteredEncoder
    {
        public final Class<? extends Encoder> encoder;
        public final Class<? extends Encoder> interfaceType;
        public final Class<?> objectType;
        public Encoder instance;
        
        public RegisteredEncoder(Class<? extends Encoder> encoder, Class<? extends Encoder> interfaceType, Class<?> objectType)
        {
            this.encoder = encoder;
            this.interfaceType = interfaceType;
            this.objectType = objectType;
        }
        
        public boolean implementsInterface(Class<? extends Encoder> type)
        {
            return interfaceType.isAssignableFrom(type);
        }
        
        public boolean isType(Class<?> type)
        {
            return objectType.isAssignableFrom(type);
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
        register(BooleanEncoder.class, Encoder.Text.class, Boolean.class);
        register(ByteEncoder.class, Encoder.Text.class, Byte.class);
        register(CharacterEncoder.class, Encoder.Text.class, Character.class);
        register(DoubleEncoder.class, Encoder.Text.class, Double.class);
        register(FloatEncoder.class, Encoder.Text.class, Float.class);
        register(IntegerEncoder.class, Encoder.Text.class, Integer.class);
        register(LongEncoder.class, Encoder.Text.class, Long.class);
        register(StringEncoder.class, Encoder.Text.class, String.class);
        
        // TEXT based [via Primitive reference]
        register(BooleanEncoder.class, Encoder.Text.class, Boolean.TYPE);
        register(ByteEncoder.class, Encoder.Text.class, Byte.TYPE);
        register(CharacterEncoder.class, Encoder.Text.class, Character.TYPE);
        register(DoubleEncoder.class, Encoder.Text.class, Double.TYPE);
        register(FloatEncoder.class, Encoder.Text.class, Float.TYPE);
        register(IntegerEncoder.class, Encoder.Text.class, Integer.TYPE);
        register(LongEncoder.class, Encoder.Text.class, Long.TYPE);
        
        // BINARY based
        register(ByteBufferEncoder.class, Encoder.Binary.class, ByteBuffer.class);
        register(ByteArrayEncoder.class, Encoder.Binary.class, byte[].class);
        
        // STREAMING based
        // Note: Streams (Writer / OutputStream) are not present here
        // as you don't write with a Stream via an encoder, you tell the
        // encoder to write an object to a Stream
        // register(WriterEncoder.class, Encoder.TextStream.class, Writer.class);
        // register(OutputStreamEncoder.class, Encoder.BinaryStream.class, OutputStream.class);
    }
    
    private void register(Class<? extends Encoder> encoderClass, Class<? extends Encoder> interfaceType, Class<?> type)
    {
        registeredEncoders.add(new RegisteredEncoder(encoderClass, interfaceType, type));
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
        
        registeredEncoders.add(new RegisteredEncoder(encoder, interfaceClass, objectType));
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
            
            registeredEncoder.instance = registeredEncoder.encoder.newInstance();
            registeredEncoder.instance.init(this.config);
            return registeredEncoder.instance;
        }
        catch (NoSuchElementException e)
        {
            throw new InvalidWebSocketException("No Encoder found for type " + type);
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new InitException("Unable to init Encoder for type:" + type.getName(), e);
        }
    }
    
    @Override
    public boolean test(Class<?> type)
    {
        return registeredEncoders.stream()
                .filter(registered -> registered.isType(type))
                .findFirst()
                .isPresent();
    }
}
