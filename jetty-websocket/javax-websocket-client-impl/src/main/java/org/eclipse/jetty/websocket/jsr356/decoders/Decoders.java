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

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.websocket.Decoder;
import javax.websocket.DeploymentException;

import org.eclipse.jetty.websocket.jsr356.utils.DeploymentTypeUtils;

public class Decoders
{
    private static final Map<Class<?>, Class<? extends Decoder>> DEFAULTS;

    static
    {
        DEFAULTS = new HashMap<>();
        DEFAULTS.put(Boolean.class,BooleanDecoder.class);
        DEFAULTS.put(Byte.class,ByteDecoder.class);
        DEFAULTS.put(Character.class,CharacterDecoder.class);
        DEFAULTS.put(Double.class,DoubleDecoder.class);
        DEFAULTS.put(Float.class,FloatDecoder.class);
        DEFAULTS.put(Integer.class,IntegerDecoder.class);
        DEFAULTS.put(Long.class,LongDecoder.class);
        DEFAULTS.put(Short.class,ShortDecoder.class);
        DEFAULTS.put(String.class,StringDecoder.class);
    }

    private final List<Class<? extends Decoder>> decoders;

    public Decoders()
    {
        this(null);
    }

    public Decoders(List<Class<? extends Decoder>> decoderClasses)
    {
        this.decoders = new ArrayList<>();
        // now add user provided
        addAll(decoderClasses);
    }

    public void add(Class<? extends Decoder> decoder)
    {
        this.decoders.add(decoder);
    }

    private void addAll(List<Class<? extends Decoder>> decoderClasses)
    {
        if (decoderClasses == null)
        {
            return;
        }

        for (Class<? extends Decoder> decoder : decoderClasses)
        {
            add(decoder);
        }
    }

    public Decoder getBinaryDecoder(Type type)
    {
        // TODO Auto-generated method stub
        return null;
    }

    public Decoder getTextDecoder(Type type) throws DeploymentException
    {
        for (Class<? extends Decoder> decoderClass : decoders)
        {
            if (!DeploymentTypeUtils.isAssignable(decoderClass,Decoder.Text.class))
            {
                continue; // not a text decoder
            }

            Type textType = DeploymentTypeUtils.getGenericType(decoderClass,Decoder.Text.class);
            if (DeploymentTypeUtils.isAssignable(type,textType))
            {
                return instantiate(decoderClass);
            }
        }

        // Found no user provided decoder, use default (if available)
        Class<? extends Decoder> decoderClass = null;
        if (type instanceof Class<?>)
        {
            Class<?> typeClass = (Class<?>)type;
            if (typeClass.isPrimitive())
            {
                typeClass = DeploymentTypeUtils.getPrimitiveClass(typeClass);
            }

            decoderClass = DEFAULTS.get(typeClass);

            if (decoderClass != null)
            {
                return instantiate(decoderClass);
            }
        }

        throw new DeploymentException("Unable to find appropriate Decoder for type: " + type);
    }

    private Decoder instantiate(Class<? extends Decoder> decoderClass) throws DeploymentException
    {
        try
        {
            return decoderClass.newInstance();
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new DeploymentException("Unable to instantiate Decoder: " + decoderClass,e);
        }
    }
}
