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

import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

public class AvailableDecoders implements Predicate<Class<?>>
{
    public static class RegisteredDecoder
    {
        public final Class<? extends Decoder> decoder;
        public final Class<? extends Decoder> interfaceType;
        public final Class<?> objectType;
        
        public RegisteredDecoder(Class<? extends Decoder> decoder, Class<? extends Decoder> interfaceType, Class<?> objectType)
        {
            this.decoder = decoder;
            this.interfaceType = interfaceType;
            this.objectType = objectType;
        }
        
        public boolean implementsInterface(Class<? extends Decoder> type)
        {
            return interfaceType.isAssignableFrom(type);
        }
        
        public boolean isType(Class<?> type)
        {
            return objectType.isAssignableFrom(type);
        }
    }
    
    private LinkedList<RegisteredDecoder> registeredDecoders;
    
    public AvailableDecoders()
    {
        registeredDecoders = new LinkedList<>();
        
        // TEXT based [via Class reference]
        register(BooleanDecoder.class, Decoder.Text.class, Boolean.class);
        register(ByteDecoder.class, Decoder.Text.class, Byte.class);
        register(CharacterDecoder.class, Decoder.Text.class, Character.class);
        register(DoubleDecoder.class, Decoder.Text.class, Double.class);
        register(FloatDecoder.class, Decoder.Text.class, Float.class);
        register(IntegerDecoder.class, Decoder.Text.class, Integer.class);
        register(LongDecoder.class, Decoder.Text.class, Long.class);
        register(StringDecoder.class, Decoder.Text.class, String.class);
        
        // TEXT based [via Primitive reference]
        register(BooleanDecoder.class, Decoder.Text.class, Boolean.TYPE);
        register(ByteDecoder.class, Decoder.Text.class, Byte.TYPE);
        register(CharacterDecoder.class, Decoder.Text.class, Character.TYPE);
        register(DoubleDecoder.class, Decoder.Text.class, Double.TYPE);
        register(FloatDecoder.class, Decoder.Text.class, Float.TYPE);
        register(IntegerDecoder.class, Decoder.Text.class, Integer.TYPE);
        register(LongDecoder.class, Decoder.Text.class, Long.TYPE);
        
        // BINARY based
        register(ByteBufferDecoder.class, Decoder.Binary.class, ByteBuffer.class);
        register(ByteArrayDecoder.class, Decoder.Binary.class, byte[].class);
        
        // STREAMING based
        register(ReaderDecoder.class, Decoder.TextStream.class, Reader.class);
        register(InputStreamDecoder.class, Decoder.BinaryStream.class, InputStreamDecoder.class);
    }
    
    private void register(Class<? extends Decoder> decoderClass, Class<? extends Decoder> interfaceType, Class<?> type)
    {
        registeredDecoders.add(new RegisteredDecoder(decoderClass, interfaceType, type));
    }
    
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
    
    private void add(Class<? extends Decoder> decoder, Class<? extends Decoder> interfaceClass)
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
        
        registeredDecoders.add(new RegisteredDecoder(decoder, interfaceClass, objectType));
    }
    
    public List<RegisteredDecoder> supporting(Class<? extends Decoder> interfaceType)
    {
        return registeredDecoders.stream()
                .filter(registered -> registered.implementsInterface(interfaceType))
                .collect(Collectors.toList());
    }
    
    public Class<? extends Decoder> getDecoderFor(Class<?> type)
    {
        try
        {
            return registeredDecoders.stream()
                    .filter(registered -> registered.isType(type))
                    .findFirst()
                    .get()
                    .decoder;
        }
        catch (NoSuchElementException e)
        {
            throw new InvalidWebSocketException("No Decoder found for type " + type);
        }
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
        return registeredDecoders.stream()
                .filter(registered -> registered.isType(type))
                .findFirst()
                .isPresent();
    }
}
