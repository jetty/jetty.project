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

package org.eclipse.jetty.websocket.javax.server.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import javax.websocket.server.PathParam;

import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.internal.util.InvokerUtils;

/**
 * Method argument identifier for {@link javax.websocket.server.PathParam} annotations.
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
                    validateType(paramType);
                    PathParam pathParam = (PathParam)anno;
                    return new InvokerUtils.Arg(paramType, pathParam.value());
                }
            }
        }
        return new InvokerUtils.Arg(paramType);
    }

    /**
     * The JSR356 rules for @PathParam only support
     * String, Primitive Types (and their Boxed version)
     */
    public static void validateType(Class<?> type)
    {
        if (!String.class.isAssignableFrom(type) &&
            !Integer.TYPE.isAssignableFrom(type) &&
            !Long.TYPE.isAssignableFrom(type) &&
            !Short.TYPE.isAssignableFrom(type) &&
            !Float.TYPE.isAssignableFrom(type) &&
            !Double.TYPE.isAssignableFrom(type) &&
            !Boolean.TYPE.isAssignableFrom(type) &&
            !Character.TYPE.isAssignableFrom(type) &&
            !Byte.TYPE.isAssignableFrom(type))
            throw new InvalidSignatureException("Unsupported PathParam Type: " + type);
    }
}
