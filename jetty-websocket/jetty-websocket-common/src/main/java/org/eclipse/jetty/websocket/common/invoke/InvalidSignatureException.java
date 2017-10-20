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
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.eclipse.jetty.websocket.core.InvalidWebSocketException;

@SuppressWarnings("serial")
public class InvalidSignatureException extends InvalidWebSocketException
{
    public static InvalidSignatureException build(Class<?> pojo, Class<? extends Annotation> methodAnnotationClass, Method method)
    {
        StringBuilder err = new StringBuilder();
        err.append("Invalid ");
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

    public static InvalidSignatureException build(MethodType expectedType, MethodType actualType)
    {
        StringBuilder err = new StringBuilder();
        err.append("Invalid MethodHandle ");
        ReflectUtils.append(err, actualType);
        err.append(" - expected ");
        ReflectUtils.append(err, expectedType);

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
