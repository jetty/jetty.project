//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import javax.websocket.server.PathParam;

import org.eclipse.jetty.websocket.jsr356.util.InvokerUtils;

/**
 * Method argument identifier for {@link javax.websocket.server.PathParam} annotations.
 */
@SuppressWarnings("unused")
public class PathParamArgIdentifier implements InvokerUtils.ParamIdentifier
{
    @Override
    public InvokerUtils.Arg getParamArg(Method method, Class<?> paramType, int idx)
    {
        Annotation annos[] = method.getParameterAnnotations()[idx];
        if (annos != null || (annos.length > 0))
        {
            for (Annotation anno : annos)
            {
                if (anno.annotationType().equals(PathParam.class))
                {
                    PathParam pathParam = (PathParam) anno;
                    return new InvokerUtils.Arg(paramType, pathParam.value());
                }
            }
        }
        return new InvokerUtils.Arg(paramType);
    }
}
