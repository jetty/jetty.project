//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.jsr356.annotations;

import java.lang.reflect.Method;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;

/**
 * Callable for {@link javax.websocket.OnOpen} annotated methods
 */
public class OnOpenCallable extends JsrCallable
{
    private int idxEndpointConfig = -1;

    public OnOpenCallable(Class<?> pojo, Method method)
    {
        super(pojo, method);
    }

    public OnOpenCallable(OnOpenCallable copy)
    {
        super(copy);
        this.idxEndpointConfig = copy.idxEndpointConfig;
    }

    public void call(Object endpoint, EndpointConfig config)
    {
        // EndpointConfig is an optional parameter
        if (idxEndpointConfig >= 0)
        {
            super.args[idxEndpointConfig] = config;
        }
        super.call(endpoint, super.args);
    }

    @Override
    public void init(JsrSession session)
    {
        idxEndpointConfig = findIndexForRole(Role.ENDPOINT_CONFIG);
        super.init(session);
    }

    @Override
    public void setDecodingType(Class<?> decodingType)
    {
        /* ignore, not relevant for onClose */
    }
}
