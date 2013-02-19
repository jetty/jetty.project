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

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.common.events.annotated.CallableMethod;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.decoders.Decoders;
import org.eclipse.jetty.websocket.jsr356.encoders.Encoders;

public class JsrMessageCallableMethod extends CallableMethod
{
    private Class<?> returnType;
    // Index of Session Parameter
    private int idxSession = -1;
    private int idxIsLast = -1;
    private int idxFormat = -1;

    public JsrMessageCallableMethod(Class<?> pojo, Method method, Encoders encoders, Decoders decoders) throws InvalidSignatureException
    {
        super(pojo,method);

        setReturnType(method.getReturnType());
    }

    public void setReturnType(Class<?> returnType)
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

        // TODO: Determine if encoder exists for this return type
    }
}
