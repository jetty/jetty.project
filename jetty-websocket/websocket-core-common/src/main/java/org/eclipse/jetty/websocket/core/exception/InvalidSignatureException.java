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

package org.eclipse.jetty.websocket.core.exception;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;

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
        if (pojo == null)
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
