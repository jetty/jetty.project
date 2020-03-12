//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.server.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import jakarta.websocket.server.PathParam;
import org.eclipse.jetty.websocket.util.InvokerUtils;

/**
 * Method argument identifier for {@link jakarta.websocket.server.PathParam} annotations.
 */
@SuppressWarnings("unused")
public class PathParamIdentifier implements InvokerUtils.ParamIdentifier
{
    @Override
    public InvokerUtils.Arg getParamArg(Method method, Class<?> paramType, int idx)
    {
        Annotation[] annos = method.getParameterAnnotations()[idx];
        if (annos != null || (annos.length > 0))
        {
            for (Annotation anno : annos)
            {
                if (anno.annotationType().equals(PathParam.class))
                {
                    PathParam pathParam = (PathParam)anno;
                    return new InvokerUtils.Arg(paramType, pathParam.value());
                }
            }
        }
        return new InvokerUtils.Arg(paramType);
    }
}
