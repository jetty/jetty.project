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

package org.eclipse.jetty.websocket.jsr356.encoders;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import javax.websocket.Encoder;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.ConfigurationException;
import org.eclipse.jetty.websocket.jsr356.utils.DeploymentTypeUtils;

public class Encoders
{
    private static class EncoderRef
    {
        Class<?> type;
        Class<? extends Encoder> encoder;

        public EncoderRef(Class<?> type, Class<? extends Encoder> encoder)
        {
            this.type = type;
            this.encoder = encoder;
        }
    }

    public static List<ParameterizedType> getEncoderInterfaces(Class<? extends Encoder> encoder)
    {
        List<ParameterizedType> ret = new ArrayList<>();
        for (Type type : encoder.getGenericInterfaces())
        {
            if (!(type instanceof ParameterizedType))
            {
                continue; // skip
            }

            ParameterizedType ptype = (ParameterizedType)type;
            if (DeploymentTypeUtils.isAssignable(type,Encoder.Text.class) || DeploymentTypeUtils.isAssignable(type,Encoder.TextStream.class)
                    || DeploymentTypeUtils.isAssignable(type,Encoder.Binary.class) || DeploymentTypeUtils.isAssignable(type,Encoder.BinaryStream.class))
            {
                ret.add(ptype);
            }
        }
        return ret;
    }

    private final List<EncoderRef> encoders;

    public Encoders()
    {
        this.encoders = new ArrayList<>();

        add(new EncoderRef(Boolean.class,BooleanEncoder.class));
        add(new EncoderRef(Byte.class,ByteEncoder.class));
        add(new EncoderRef(Character.class,CharacterEncoder.class));
        add(new EncoderRef(Double.class,DoubleEncoder.class));
        add(new EncoderRef(Float.class,FloatEncoder.class));
        add(new EncoderRef(Integer.class,IntegerEncoder.class));
        add(new EncoderRef(Long.class,LongEncoder.class));
        add(new EncoderRef(Short.class,ShortEncoder.class));
        add(new EncoderRef(String.class,StringEncoder.class));
    }

    public Encoders(Class<? extends Encoder> encoderClasses[])
    {
        this();

        if (encoderClasses != null)
        {
            // now add user provided encoders
            for (Class<? extends Encoder> encoder : encoderClasses)
            {
                add(encoder);
            }
        }
    }

    public Encoders(List<Class<? extends Encoder>> encoderClasses)
    {
        this();
        if (encoderClasses != null)
        {
            // now add user provided encoders
            for (Class<? extends Encoder> encoder : encoderClasses)
            {
                add(encoder);
            }
        }
    }

    public void add(Class<? extends Encoder> encoder)
    {
        for (ParameterizedType iencoder : getEncoderInterfaces(encoder))
        {
            Type handledTypes[] = iencoder.getActualTypeArguments();
            if (handledTypes == null)
            {
                throw new InvalidSignatureException(encoder + " has invalid signature for " + iencoder + " Generic type is null");
            }
            if (handledTypes.length != 1)
            {
                throw new InvalidSignatureException(encoder + " has invalid signature for " + iencoder + " - multi-value generic types not supported");
            }
            Type handledType = handledTypes[0];
            if (handledType instanceof Class<?>)
            {
                Class<?> handler = (Class<?>)handledType;
                add(handler,encoder);
            }
            else
            {
                throw new InvalidSignatureException(encoder + " has invalid signature for " + iencoder + " - only java.lang.Class based generics supported");
            }
        }
    }

    private void add(Class<?> handler, Class<? extends Encoder> encoder)
    {
        // verify that we are not adding a duplicate
        for (EncoderRef ref : encoders)
        {
            if (DeploymentTypeUtils.isAssignableClass(handler,ref.type))
            {
                throw new ConfigurationException("Duplicate Encoder handling for type " + ref.type + ": found in " + ref.encoder + " and " + encoder);
            }
        }
        // add entry
        this.encoders.add(new EncoderRef(handler,encoder));
    }

    private void add(EncoderRef ref)
    {
        this.encoders.add(ref);
    }

    public Encoder getEncoder(Class<?> type)
    {
        Class<?> targetType = type;
        if (targetType.isPrimitive())
        {
            targetType = DeploymentTypeUtils.getPrimitiveClass(targetType);
        }

        for (EncoderRef ref : encoders)
        {
            if (DeploymentTypeUtils.isAssignable(targetType,ref.type))
            {
                return instantiate(ref.encoder);
            }
        }

        throw new InvalidSignatureException("Unable to find appropriate Encoder for type: " + type);
    }

    private Encoder instantiate(Class<? extends Encoder> encoderClass)
    {
        try
        {
            return encoderClass.newInstance();
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new ConfigurationException("Unable to instantiate Encoder: " + encoderClass,e);
        }
    }
}
