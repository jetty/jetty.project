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

package org.eclipse.jetty.websocket.common.function;

import java.lang.reflect.Method;
import java.util.function.Function;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.reflect.Arg;
import org.eclipse.jetty.websocket.common.reflect.DynamicArgs;
import org.eclipse.jetty.websocket.common.reflect.ExactSignature;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

/**
 * Jetty {@link WebSocket} {@link OnWebSocketConnect} method {@link Function}
 */
public class OnOpenFunction<T extends Session> implements Function<T, Void>
{
    private static final DynamicArgs.Builder ARGBUILDER;
    private static final Arg ARG_SESSION = new Arg(1, Session.class);

    static
    {
        ARGBUILDER = new DynamicArgs.Builder();
        ARGBUILDER.addSignature(new ExactSignature());
        ARGBUILDER.addSignature(new ExactSignature(ARG_SESSION));
    }

    private final Object endpoint;
    private final Method method;
    private final DynamicArgs callable;

    public OnOpenFunction(Object endpoint, Method method)
    {
        this.endpoint = endpoint;
        this.method = method;

        ReflectUtils.assertIsAnnotated(method, OnWebSocketConnect.class);
        ReflectUtils.assertIsPublicNonStatic(method);
        ReflectUtils.assertIsReturn(method, Void.TYPE);

        this.callable = ARGBUILDER.build(method, ARG_SESSION);
        if (this.callable == null)
        {
            throw InvalidSignatureException.build(method, OnWebSocketConnect.class, ARGBUILDER);
        }
    }

    @Override
    public Void apply(Session session)
    {
        this.callable.invoke(endpoint, session);
        return null;
    }
}
