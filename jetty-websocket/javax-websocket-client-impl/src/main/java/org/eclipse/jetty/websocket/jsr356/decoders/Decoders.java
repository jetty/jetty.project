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

package org.eclipse.jetty.websocket.jsr356.decoders;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.ConfigurationException;
import org.eclipse.jetty.websocket.jsr356.utils.DeploymentTypeUtils;

public class Decoders implements Iterable<DecoderRef>
{
    public static List<ParameterizedType> getDecoderInterfaces(Class<? extends Decoder> decoder)
    {
        List<ParameterizedType> ret = new ArrayList<>();
        for (Type type : decoder.getGenericInterfaces())
        {
            if (!(type instanceof ParameterizedType))
            {
                continue; // skip
            }

            ParameterizedType ptype = (ParameterizedType)type;
            if (DeploymentTypeUtils.isAssignable(type,Decoder.Text.class) || DeploymentTypeUtils.isAssignable(type,Decoder.TextStream.class)
                    || DeploymentTypeUtils.isAssignable(type,Decoder.Binary.class) || DeploymentTypeUtils.isAssignable(type,Decoder.BinaryStream.class))
            {
                ret.add(ptype);
            }
        }
        return ret;
    }

    private final List<DecoderRef> decoders;

    public Decoders()
    {
        this.decoders = new ArrayList<>();

        // Default TEXT Message Decoders
        add(new DecoderRef(Boolean.class,BooleanDecoder.class));
        add(new DecoderRef(Byte.class,ByteDecoder.class));
        add(new DecoderRef(Character.class,CharacterDecoder.class));
        add(new DecoderRef(Double.class,DoubleDecoder.class));
        add(new DecoderRef(Float.class,FloatDecoder.class));
        add(new DecoderRef(Integer.class,IntegerDecoder.class));
        add(new DecoderRef(Long.class,LongDecoder.class));
        add(new DecoderRef(Short.class,ShortDecoder.class));
        add(new DecoderRef(String.class,StringDecoder.class));

        // Default BINARY Message Decoders
        add(new DecoderRef(ByteBuffer.class,ByteBufferDecoder.class));
    }

    public Decoders(Class<? extends Decoder>[] decoderClasses)
    {
        this();

        if (decoderClasses != null)
        {
            // now add user provided
            for (Class<? extends Decoder> decoder : decoderClasses)
            {
                add(decoder);
            }
        }
    }

    public Decoders(List<Class<? extends Decoder>> decoderClasses)
    {
        this();

        if (decoderClasses != null)
        {
            // now add user provided
            for (Class<? extends Decoder> decoder : decoderClasses)
            {
                add(decoder);
            }
        }
    }

    public void add(Class<? extends Decoder> decoder)
    {
        for (ParameterizedType idecoder : getDecoderInterfaces(decoder))
        {
            Type handledTypes[] = idecoder.getActualTypeArguments();
            if (handledTypes == null)
            {
                throw new InvalidSignatureException(decoder + " has invalid signature for " + idecoder + " Generic type is null");
            }
            if (handledTypes.length != 1)
            {
                throw new InvalidSignatureException(decoder + " has invalid signature for " + idecoder + " - multi-value generic types not supported");
            }
            Type handledType = handledTypes[0];
            if (handledType instanceof Class<?>)
            {
                Class<?> handler = (Class<?>)handledType;
                add(handler,decoder);
            }
            else
            {
                throw new InvalidSignatureException(decoder + " has invalid signature for " + idecoder + " - only java.lang.Class based generics supported");
            }
        }
    }

    private void add(Class<?> handler, Class<? extends Decoder> decoder)
    {
        // verify that we are not adding a duplicate
        for (DecoderRef ref : decoders)
        {
            if (DeploymentTypeUtils.isAssignableClass(handler,ref.getType()))
            {
                StringBuilder err = new StringBuilder();
                err.append("Duplicate Decoder handling for type ");
                err.append(ref.getType());
                err.append(": found in ");
                err.append(ref.getDecoder()).append(" and ");
                err.append(decoder);
                throw new ConfigurationException(err.toString());
            }
        }
        // add entry
        this.decoders.add(new DecoderRef(handler,decoder));
    }

    private void add(DecoderRef ref)
    {
        this.decoders.add(ref);
    }

    public Decoder getDecoder(Class<?> type)
    {
        Class<?> targetType = type;
        if (targetType.isPrimitive())
        {
            targetType = DeploymentTypeUtils.getPrimitiveClass(targetType);
        }

        for (DecoderRef ref : decoders)
        {
            if (DeploymentTypeUtils.isAssignable(targetType,ref.getType()))
            {
                return instantiate(ref.getDecoder());
            }
        }

        throw new InvalidSignatureException("Unable to find appropriate Decoder for type: " + type);
    }

    private Decoder instantiate(Class<? extends Decoder> decoderClass)
    {
        try
        {
            return decoderClass.newInstance();
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new ConfigurationException("Unable to instantiate Decoder: " + decoderClass,e);
        }
    }

    @Override
    public Iterator<DecoderRef> iterator()
    {
        return decoders.iterator();
    }
}
