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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;

import javax.websocket.Decoder;
import javax.websocket.Encoder;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.common.events.annotated.CallableMethod;

public class JsrMessageCallableMethod extends CallableMethod
{
    private Class<?> returnType;

    public JsrMessageCallableMethod(Class<?> pojo, Method method)
    {
        super(pojo,method);
    }

    public void setReturnType(Class<?> returnType, Class<? extends Encoder> encoders[])
    {
        if (Void.TYPE.equals(returnType))
        {
            // Void type
            this.returnType = returnType;
            return;
        }

        if (returnType.isArray() && Byte.TYPE.equals(returnType))
        {
            // A byte array
            this.returnType = returnType;
            return;
        }

        if (TypeUtil.toName(returnType) != null)
        {
            // A primitive (including String)
            this.returnType = returnType;
            return;
        }

        if (ByteBuffer.class.isAssignableFrom(returnType))
        {
            // A nio ByteBuffer
            this.returnType = returnType;
            return;
        }

        // Determine if encoder exists for this return type
        for (Class<? extends Encoder> encoder : encoders)
        {
            
        }
    }
}
