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

package org.eclipse.jetty.websocket.common.functions;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Function;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.DynamicArgs;
import org.eclipse.jetty.websocket.common.util.ExactSignature;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

/**
 * Jetty {@link WebSocket} {@link OnWebSocketConnect} method {@link Function}
 */
public class OnOpenFunction implements Function<Session, Void>
{
    private static final DynamicArgs.Builder ARGBUILDER;
    private static final int SESSION = 1;

    static
    {
        ARGBUILDER = new DynamicArgs.Builder();
        ARGBUILDER.addSignature(new ExactSignature().indexedAs());
        ARGBUILDER.addSignature(new ExactSignature(Session.class).indexedAs(SESSION));
    }

    private final Object endpoint;
    private final Method method;
    private final DynamicArgs callable;

    public OnOpenFunction(Object endpoint, Method method)
    {
        this.endpoint = endpoint;
        this.method = method;

        ReflectUtils.assertIsAnnotated(method,OnWebSocketConnect.class);
        ReflectUtils.assertIsPublicNonStatic(method);
        ReflectUtils.assertIsReturn(method,Void.TYPE);

        this.callable = ARGBUILDER.build(method);
        if (this.callable == null)
        {
            throw InvalidSignatureException.build(method,OnWebSocketConnect.class,ARGBUILDER);
        }
        this.callable.setArgReferences(SESSION);

    }

    @Override
    public Void apply(Session session)
    {
        Object args[] = this.callable.toArgs(session);
        try
        {
            method.invoke(endpoint,args);
        }
        catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
        {
            throw new WebSocketException("Unable to call method " + ReflectUtils.toString(endpoint.getClass(),method),e);
        }
        return null;
    }
}
