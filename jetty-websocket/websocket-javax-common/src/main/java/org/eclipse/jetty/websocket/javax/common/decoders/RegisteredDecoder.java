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

package org.eclipse.jetty.websocket.javax.common.decoders;

import java.lang.reflect.InvocationTargetException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.javax.common.InitException;

public class RegisteredDecoder
{
    // The user supplied Decoder class
    public final Class<? extends Decoder> decoder;
    // The javax.websocket.Decoder.* type (eg: Decoder.Binary, Decoder.BinaryStream, Decoder.Text, Decoder.TextStream)
    public final Class<? extends Decoder> interfaceType;
    public final Class<?> objectType;
    public final boolean primitive;
    public final EndpointConfig config;

    private Decoder instance;

    public RegisteredDecoder(Class<? extends Decoder> decoder, Class<? extends Decoder> interfaceType, Class<?> objectType, EndpointConfig endpointConfig)
    {
        this(decoder, interfaceType, objectType, endpointConfig, false);
    }

    public RegisteredDecoder(Class<? extends Decoder> decoder, Class<? extends Decoder> interfaceType, Class<?> objectType, EndpointConfig endpointConfig, boolean primitive)
    {
        this.decoder = decoder;
        this.interfaceType = interfaceType;
        this.objectType = objectType;
        this.primitive = primitive;
        this.config = endpointConfig;
    }

    public boolean implementsInterface(Class<? extends Decoder> type)
    {
        return interfaceType.isAssignableFrom(type);
    }

    public boolean isType(Class<?> type)
    {
        return objectType.isAssignableFrom(type);
    }

    public <T extends Decoder> T getInstance()
    {
        if (instance == null)
        {
            try
            {
                instance = decoder.getConstructor().newInstance();
                instance.init(config);
                return (T)instance;
            }
            catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e)
            {
                throw new InitException("Unable to init Decoder for type:" + decoder.getName(), e);
            }
        }

        return (T)instance;
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append(RegisteredDecoder.class.getSimpleName());
        str.append('[').append(decoder.getName());
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
