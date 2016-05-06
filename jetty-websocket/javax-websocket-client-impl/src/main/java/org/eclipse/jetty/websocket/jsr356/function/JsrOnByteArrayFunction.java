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

package org.eclipse.jetty.websocket.jsr356.function;

import java.lang.reflect.Method;
import java.util.function.Function;

import javax.websocket.OnMessage;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.reflect.Arg;
import org.eclipse.jetty.websocket.common.reflect.DynamicArgs;
import org.eclipse.jetty.websocket.common.reflect.UnorderedSignature;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

/**
 * javax.websocket {@link OnMessage} method {@link Function} for BINARY/byte[] types
 */
@Deprecated
public class JsrOnByteArrayFunction implements Function<byte[], Void>
{
    private static final Arg ARG_SESSION = new Arg(Session.class);
    private static final Arg ARG_BUFFER = new Arg(byte[].class);
    private static final Arg ARG_OFFSET = new Arg(int.class);
    private static final Arg ARG_LENGTH = new Arg(int.class);

    private final Arg[] extraArgs;
    private final int paramCount;
    private final Session session;
    private final Object endpoint;
    private final Method method;
    private final DynamicArgs callable;

    public JsrOnByteArrayFunction(Session session, Object endpoint, Method method, Arg[] extraArgs)
    {
        this.session = session;
        this.endpoint = endpoint;
        this.method = method;
        this.extraArgs = extraArgs;

        // Validate Method
        ReflectUtils.assertIsAnnotated(method, OnMessage.class);
        ReflectUtils.assertIsPublicNonStatic(method);
        // FIXME: Need to support any return!
        ReflectUtils.assertIsReturn(method, Void.TYPE);

        // Build up dynamic callable
        DynamicArgs.Builder argBuilder = new DynamicArgs.Builder();
        int argCount = 4;
        if (this.extraArgs != null)
            argCount += extraArgs.length;

        this.paramCount = argCount;

        Arg[] callArgs = new Arg[argCount];
        int idx = 0;
        callArgs[idx++] = ARG_SESSION;
        callArgs[idx++] = ARG_BUFFER;
        callArgs[idx++] = ARG_OFFSET;
        callArgs[idx++] = ARG_LENGTH;
        for (Arg arg : this.extraArgs)
        {
            callArgs[idx++] = arg;
        }

        argBuilder.addSignature(new UnorderedSignature(callArgs));

        // Attempt to build callable
        this.callable = argBuilder.build(method);
        if (this.callable == null)
        {
            throw InvalidSignatureException.build(method, OnMessage.class, argBuilder);
        }
    }

    @Override
    public Void apply(byte[] bin)
    {
        Object params[] = new Object[paramCount];
        int idx = 0;
        params[idx++] = session;
        params[idx++] = bin;
        params[idx++] = 0;
        params[idx++] = bin.length;
        // TODO: add PathParam Arg Values?

        this.callable.invoke(endpoint, params);
        return null;
    }
}
