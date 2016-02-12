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
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.DynamicArgs;
import org.eclipse.jetty.websocket.common.util.DynamicArgs.ExactSignature;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

/**
 * Jetty {@link WebSocket} {@link OnWebSocketMessage} method {@link Function} for BINARY/byte[] types
 */
public class OnByteArrayFunction implements Function<byte[], Void>
{
    private static final DynamicArgs.Builder ARGBUILDER;
    private static final int SESSION = 1;
    private static final int BUFFER = 2;
    private static final int OFFSET = 3;
    private static final int LENGTH = 4;

    static
    {
        ARGBUILDER = new DynamicArgs.Builder();
        ARGBUILDER.addSignature(new ExactSignature(byte[].class).indexedAs(BUFFER));
        ARGBUILDER.addSignature(new ExactSignature(byte[].class,int.class,int.class).indexedAs(BUFFER,OFFSET,LENGTH));
        ARGBUILDER.addSignature(new ExactSignature(Session.class,byte[].class).indexedAs(SESSION,BUFFER));
        ARGBUILDER.addSignature(new ExactSignature(Session.class,byte[].class,int.class,int.class).indexedAs(SESSION,BUFFER,OFFSET,LENGTH));
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

        ReflectUtils.assertIsAnnotated(method,OnWebSocketMessage.class);
        ReflectUtils.assertIsPublicNonStatic(method);
        ReflectUtils.assertIsReturn(method,Void.TYPE);

        this.callable = ARGBUILDER.build(method);
        if (this.callable == null)
        {
            throw InvalidSignatureException.build(method,OnWebSocketMessage.class,ARGBUILDER);
        }
        this.callable.setArgReferences(SESSION,BUFFER,OFFSET,LENGTH);
    }

    @Override
    public Void apply(byte[] bin)
    {
        Object args[] = this.callable.toArgs(session,bin,0,bin.length);
        try
        {
            method.invoke(endpoint,args);
        }
        catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
        {
            throw new WebSocketException("Unable to call text message method " + ReflectUtils.toString(endpoint.getClass(),method),e);
        }
        return null;
    }
}
