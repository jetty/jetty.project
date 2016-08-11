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
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

/**
 * javax.websocket {@link OnMessage} method {@link Function} for TEXT/{@link String} types
 */
public class JsrOnTextFunction<T> implements Function<T, Object>
{
    private static final DynamicArgs.Builder ARGBUILDER;
    private static final Arg SESSION = new Arg(Session.class);
    private static final Arg TEXT = new Arg(String.class).required();
    
    static
    {
        ARGBUILDER = new DynamicArgs.Builder();
        ARGBUILDER.addSignature(SESSION, TEXT);
    }
    
    public static boolean hasMatchingSignature(Method method)
    {
        return ARGBUILDER.hasMatchingSignature(method);
    }
    
    private final Session session;
    private final Object endpoint;
    private final Method method;
    private final DynamicArgs callable;
    
    public JsrOnTextFunction(Session session, Object endpoint, Method method)
    {
        this.session = session;
        this.endpoint = endpoint;
        this.method = method;
        
        ReflectUtils.assertIsAnnotated(method, OnMessage.class);
        ReflectUtils.assertIsPublicNonStatic(method);
        ReflectUtils.assertIsReturn(method, Void.TYPE);
        
        this.callable = ARGBUILDER.build(method, SESSION, TEXT);
        if (this.callable == null)
        {
            throw InvalidSignatureException.build(method, OnMessage.class, ARGBUILDER);
        }
    }
    
    @Override
    public Object apply(T text)
    {
        return this.callable.invoke(endpoint, session, text);
    }
}
