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
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.reflect.Arg;
import org.eclipse.jetty.websocket.common.reflect.DynamicArgs;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

/**
 * Jetty {@link WebSocket} {@link OnWebSocketMessage} method {@link Function} for BINARY/byte[] types
 */
public class OnByteArrayFunction implements Function<byte[], Void>
{
    private static final DynamicArgs.Builder ARGBUILDER;
    private static final Arg ARG_SESSION = new Arg(1, Session.class);
    private static final Arg ARG_BUFFER = new Arg(2, byte[].class).required();
    private static final Arg ARG_OFFSET = new Arg(3, int.class);
    private static final Arg ARG_LENGTH = new Arg(4, int.class);

    static
    {
        ARGBUILDER = new DynamicArgs.Builder();
        ARGBUILDER.addSignature(ARG_SESSION, ARG_BUFFER, ARG_OFFSET, ARG_LENGTH);
    }

    public static DynamicArgs.Builder getDynamicArgsBuilder()
    {
        return ARGBUILDER;
    }

    public static boolean hasMatchingSignature(Method method)
    {
        return ARGBUILDER.hasMatchingSignature(method);
    }

    private final Session session;
    private final Object endpoint;
    private final Method method;
    private final DynamicArgs callable;

    public OnByteArrayFunction(Session session, Object endpoint, Method method)
    {
        this.session = session;
        this.endpoint = endpoint;
        this.method = method;

        ReflectUtils.assertIsAnnotated(method, OnWebSocketMessage.class);
        ReflectUtils.assertIsPublicNonStatic(method);
        ReflectUtils.assertIsReturn(method, Void.TYPE);

        this.callable = ARGBUILDER.build(method, ARG_SESSION, ARG_BUFFER, ARG_OFFSET, ARG_LENGTH);
        if (this.callable == null)
        {
            throw InvalidSignatureException.build(method, OnWebSocketMessage.class, ARGBUILDER);
        }
    }

    @Override
    public Void apply(byte[] bin)
    {
        this.callable.invoke(endpoint, this.session, bin, 0, bin.length);
        return null;
    }
}
