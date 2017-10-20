//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.invoke;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.eclipse.jetty.util.annotation.Name;

/**
 * Simple {@link org.eclipse.jetty.websocket.common.invoke.InvokerUtils.ParamIdentifier}
 * that observes {@link Name} tagged method parameters.
 */
public class NameParamIdentifier implements InvokerUtils.ParamIdentifier
{
    @Override
    public InvokerUtils.Arg getParamArg(Method method, Class<?> paramType, int idx)
    {
        Annotation annos[] = method.getParameterAnnotations()[idx];
        if (annos != null || (annos.length > 0))
        {
            for (Annotation anno : annos)
            {
                if (anno.annotationType().equals(Name.class))
                {
                    Name name = (Name) anno;
                    return new InvokerUtils.Arg(paramType, name.value());
                }
            }
        }

        return new InvokerUtils.Arg(paramType);
    }
}
