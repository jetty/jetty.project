//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server;

import java.lang.reflect.Method;

import javax.websocket.server.PathParam;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.decoders.Decoders;
import org.eclipse.jetty.websocket.jsr356.encoders.Encoders;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrMessageCallableMethod;

public class JsrServerMessageCallableMethod extends JsrMessageCallableMethod
{
    public JsrServerMessageCallableMethod(Class<?> pojo, Method method, Encoders encoders, Decoders decoders) throws InvalidSignatureException
    {
        super(pojo,method,encoders,decoders);
    }

    @Override
    public String getPathParam(Class<?> paramType)
    {
        PathParam pathParam = paramType.getAnnotation(PathParam.class);
        if (pathParam == null)
        {
            return null;
        }
        return pathParam.value();
    }
}
