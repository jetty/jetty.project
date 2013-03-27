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

package org.eclipse.jetty.websocket.jsr356.annotations;

import java.lang.reflect.Method;
import java.util.Map;

import javax.websocket.EndpointConfig;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;

/**
 * Callable for {@link OnOpen} annotated methods
 */
public class OnOpenCallable extends JsrCallable
{
    private int idxEndpointConfig = -1;

    public OnOpenCallable(Class<?> pojo, Method method)
    {
        super(pojo,method);
    }

    public void call(Object endpoint, EndpointConfig config)
    {
        // EndpointConfig is an optional parameter
        if (idxEndpointConfig >= 0)
        {
            super.args[idxEndpointConfig] = config;
        }
        super.call(endpoint,super.args);
    }

    public OnOpenCallable copy()
    {
        OnOpenCallable copy = new OnOpenCallable(pojo,method);
        super.copyTo(copy);
        copy.idxEndpointConfig = this.idxEndpointConfig;
        return copy;
    }

    @Override
    public void init(Session session, Map<String, String> pathParams)
    {
        idxEndpointConfig = findIndexForRole(Role.ENDPOINT_CONFIG);
        super.init(session,pathParams);
    }
}
