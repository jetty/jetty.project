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

import jakarta.websocket.Encoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegisteredEncoder
{
    private static final Logger LOG = LoggerFactory.getLogger(RegisteredEncoder.class);

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

    public void destroyInstance()
    {
        if (instance != null)
        {
            try
            {
                instance.destroy();
            }
            catch (Throwable t)
            {
                LOG.warn("Error destroying Decoder", t);
            }

            instance = null;
        }
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append(RegisteredEncoder.class.getSimpleName());
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
