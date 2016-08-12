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
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.reflect.Arg;
import org.eclipse.jetty.websocket.common.reflect.DynamicArgs;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

/**
 * Jetty {@link WebSocket} {@link OnWebSocketClose} method {@link Function}
 */
public class OnCloseFunction implements Function<CloseInfo, Void>
{
    private static final DynamicArgs.Builder ARGBUILDER;
    private static final Arg ARG_SESSION = new Arg(1, Session.class);
    private static final Arg ARG_STATUS_CODE = new Arg(2, int.class);
    private static final Arg ARG_REASON = new Arg(3, String.class);

    static
    {
        ARGBUILDER = new DynamicArgs.Builder();
        ARGBUILDER.addSignature(ARG_SESSION, ARG_STATUS_CODE, ARG_REASON);
    }

    private final Session session;
    private final Object endpoint;
    private final Method method;
    private final DynamicArgs callable;

    public OnCloseFunction(Session session, Object endpoint, Method method)
    {
        this.session = session;
        this.endpoint = endpoint;
        this.method = method;

        ReflectUtils.assertIsAnnotated(method, OnWebSocketClose.class);
        ReflectUtils.assertIsPublicNonStatic(method);
        ReflectUtils.assertIsReturn(method, Void.TYPE);

        this.callable = ARGBUILDER.build(method, ARG_SESSION, ARG_STATUS_CODE, ARG_REASON);
        if (this.callable == null)
        {
            throw InvalidSignatureException.build(endpoint.getClass(), OnWebSocketClose.class, method);
        }
    }

    @Override
    public Void apply(CloseInfo closeinfo)
    {
        this.callable.invoke(endpoint, session, closeinfo.getStatusCode(), closeinfo.getReason());
        return null;
    }
}
