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

import java.util.ArrayList;
import java.util.List;

import javax.websocket.Encoder;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.ConfigurationException;
import org.eclipse.jetty.websocket.jsr356.utils.DeploymentTypeUtils;
import org.eclipse.jetty.websocket.jsr356.utils.ReflectUtils;

public class Encoders
{
    private static final List<Class<?>> TYPES = new ArrayList<>();

    static
    {
        TYPES.add(Encoder.Text.class);
        TYPES.add(Encoder.TextStream.class);
        TYPES.add(Encoder.Binary.class);
        TYPES.add(Encoder.BinaryStream.class);
    }

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
        for (Class<?> type : TYPES)
        {
            Class<?> encoderClass = ReflectUtils.findGenericClassFor(encoder,type);
            if (encoderClass != null)
            {
                add(encoderClass,encoder);
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
            if (ref.type.isAssignableFrom(type))
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
