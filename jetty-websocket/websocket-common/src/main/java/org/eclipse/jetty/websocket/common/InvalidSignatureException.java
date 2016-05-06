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

package org.eclipse.jetty.websocket.common;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.reflect.DynamicArgs;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

@SuppressWarnings("serial")
public class InvalidSignatureException extends InvalidWebSocketException
{
    public static InvalidSignatureException build(Class<?> pojo, Class<? extends Annotation> methodAnnotationClass, Method method)
    {
        StringBuilder err = new StringBuilder();
        if (methodAnnotationClass != null)
        {
            err.append("@");
            err.append(methodAnnotationClass.getSimpleName());
            err.append(' ');
        }
        if (pojo != null)
        {
            ReflectUtils.append(err, method);
        }
        else
        {
            ReflectUtils.append(err, pojo, method);
        }
        return new InvalidSignatureException(err.toString());
    }

    public static InvalidSignatureException build(Method method, Class<? extends Annotation> annoClass, DynamicArgs.Builder... dynArgsBuilders)
    {
        // Build big detailed exception to help the developer
        StringBuilder err = new StringBuilder();
        err.append("Invalid declaration of ");
        err.append(method);
        err.append(System.lineSeparator());

        err.append("Acceptable #").append(method.getName());
        err.append("() argument declarations for @");
        err.append(annoClass.getSimpleName());
        err.append(" are:");
        for (DynamicArgs.Builder argsBuilder : dynArgsBuilders)
        {
            argsBuilder.appendDescription(err);
        }
        return new InvalidSignatureException(err.toString());
    }

    public InvalidSignatureException(String message)
    {
        super(message);
    }

    public InvalidSignatureException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
