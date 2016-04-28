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

package org.eclipse.jetty.websocket.jsr356.functions;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Function;

import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.common.FunctionCallException;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.DynamicArgs;
import org.eclipse.jetty.websocket.common.util.DynamicArgs.Arg;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.eclipse.jetty.websocket.common.util.UnorderedSignature;

/**
 * javax.websocket {@link OnOpen} method {@link Function}
 */
public class JsrOnOpenFunction implements Function<org.eclipse.jetty.websocket.api.Session, Void>
{
    private static final Arg ARG_SESSION = new Arg(Session.class);

    private final Arg[] extraArgs;
    private final int paramCount;
    private final Session session;
    private final Object endpoint;
    private final Method method;
    private final DynamicArgs callable;

    public JsrOnOpenFunction(Session session, Object endpoint, Method method, DynamicArgs.Arg[] extraArgs)
    {
        this.session = session;
        this.endpoint = endpoint;
        this.method = method;
        this.extraArgs = extraArgs;

        // Validate Method
        ReflectUtils.assertIsAnnotated(method, OnOpen.class);
        ReflectUtils.assertIsPublicNonStatic(method);
        ReflectUtils.assertIsReturn(method, Void.TYPE);

        // Build up dynamic callable
        DynamicArgs.Builder argBuilder = new DynamicArgs.Builder();
        int argCount = 1;
        if (this.extraArgs != null)
            argCount += extraArgs.length;

        this.paramCount = argCount;

        Arg[] callArgs = new Arg[argCount];
        int idx = 0;
        callArgs[idx++] = ARG_SESSION;
        for (Arg arg : this.extraArgs)
        {
            callArgs[idx++] = arg;
        }

        argBuilder.addSignature(new UnorderedSignature(callArgs));

        // Attempt to build callable
        this.callable = argBuilder.build(method, callArgs);
        if (this.callable == null)
        {
            throw InvalidSignatureException.build(method, OnOpen.class, argBuilder);
        }
    }

    @Override
    public Void apply(org.eclipse.jetty.websocket.api.Session sess)
    {
        Object params[] = new Object[paramCount];
        int idx = 0;
        params[idx++] = session;
        // TODO: add PathParam Arg Values?

        try
        {
            method.invoke(endpoint, params);
        }
        catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
        {
            throw new FunctionCallException("Unable to call method " + ReflectUtils.toString(endpoint.getClass(), method), e);
        }
        return null;
    }
}
