//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.jakarta.common.util;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.websocket.core.internal.util.InvokerUtils;

/**
 * Simple {@link InvokerUtils.ParamIdentifier}
 * that observes {@link Name} tagged method parameters.
 */
public class NameParamIdentifier implements InvokerUtils.ParamIdentifier
{
    @Override
    public InvokerUtils.Arg getParamArg(Method method, Class<?> paramType, int idx)
    {
        Annotation[] annos = method.getParameterAnnotations()[idx];
        if (annos != null || (annos.length > 0))
        {
            for (Annotation anno : annos)
            {
                if (anno.annotationType().equals(Name.class))
                {
                    Name name = (Name)anno;
                    return new InvokerUtils.Arg(paramType, name.value());
                }
            }
        }

        return new InvokerUtils.Arg(paramType);
    }
}
