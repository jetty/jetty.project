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
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.DynamicArgs;
import org.eclipse.jetty.websocket.common.util.ExactSignature;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

/**
 * Jetty {@link WebSocket} {@link OnWebSocketError} method {@link Function}
 */
public class OnErrorFunction implements Function<Throwable, Void>
{
    private static final DynamicArgs.Builder ARGBUILDER;
    private static final int SESSION = 1;
    private static final int CAUSE = 2;

    static
    {
        ARGBUILDER = new DynamicArgs.Builder();
        ARGBUILDER.addSignature(new ExactSignature(Throwable.class).indexedAs(CAUSE));
        ARGBUILDER.addSignature(new ExactSignature(Session.class,Throwable.class).indexedAs(SESSION,CAUSE));
    }

    private final Session session;
    private final Object endpoint;
    private final Method method;
    private final DynamicArgs callable;

    public OnErrorFunction(Session session, Object endpoint, Method method)
    {
        this.session = session;
        this.endpoint = endpoint;
        this.method = method;

        ReflectUtils.assertIsAnnotated(method,OnWebSocketError.class);
        ReflectUtils.assertIsPublicNonStatic(method);
        ReflectUtils.assertIsReturn(method,Void.TYPE);

        this.callable = ARGBUILDER.build(method);
        if (this.callable == null)
        {
            throw InvalidSignatureException.build(method,OnWebSocketError.class,ARGBUILDER);
        }
        this.callable.setArgReferences(SESSION,CAUSE);
    }

    @Override
    public Void apply(Throwable cause)
    {
        Object args[] = this.callable.toArgs(session,cause);
        try
        {
            method.invoke(endpoint,args);
        }
        catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
        {
            throw new WebSocketException("Unable to call error method " + ReflectUtils.toString(endpoint.getClass(),method),e);
        }
        return null;
    }
}
