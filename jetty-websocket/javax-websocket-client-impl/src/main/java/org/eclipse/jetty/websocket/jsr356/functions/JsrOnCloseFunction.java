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

import javax.websocket.OnClose;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.DynamicArgs;
import org.eclipse.jetty.websocket.common.util.ExactSignature;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

/**
 * javax.websocket {@link OnClose} method {@link Function}
 */
public class JsrOnCloseFunction implements Function<CloseInfo, Void>
{
    private static final DynamicArgs.Builder ARGBUILDER;
    private static final int SESSION = 1;
    private static final int STATUS_CODE = 2;
    private static final int REASON = 3;

    static
    {
        ARGBUILDER = new DynamicArgs.Builder();
        ARGBUILDER.addSignature(new ExactSignature().indexedAs());
        ARGBUILDER.addSignature(new ExactSignature(Session.class).indexedAs(SESSION));
        ARGBUILDER.addSignature(new ExactSignature(int.class,String.class).indexedAs(STATUS_CODE,REASON));
        ARGBUILDER.addSignature(new ExactSignature(Session.class,int.class,String.class).indexedAs(SESSION,STATUS_CODE,REASON));
    }

    private final Session session;
    private final Object endpoint;
    private final Method method;
    private final DynamicArgs callable;

    public JsrOnCloseFunction(Session session, Object endpoint, Method method)
    {
        this.session = session;
        this.endpoint = endpoint;
        this.method = method;

        ReflectUtils.assertIsAnnotated(method,OnClose.class);
        ReflectUtils.assertIsPublicNonStatic(method);
        ReflectUtils.assertIsReturn(method,Void.TYPE);

        this.callable = ARGBUILDER.build(method);
        if (this.callable == null)
        {
            throw InvalidSignatureException.build(method,OnClose.class,ARGBUILDER);
        }
        this.callable.setArgReferences(SESSION,STATUS_CODE,REASON);
    }

    @Override
    public Void apply(CloseInfo closeinfo)
    {
        Object args[] = this.callable.toArgs(session,closeinfo.getStatusCode(),closeinfo.getReason());
        try
        {
            method.invoke(endpoint,args);
        }
        catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
        {
            throw new WebSocketException("Unable to call close method " + ReflectUtils.toString(endpoint.getClass(),method),e);
        }
        return null;
    }
}
