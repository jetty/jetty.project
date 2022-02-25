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
import java.lang.reflect.Method;

import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;

@SuppressWarnings("serial")
public class DuplicateAnnotationException extends InvalidWebSocketException
{
    public static DuplicateAnnotationException build(Class<?> pojo, Class<? extends Annotation> annoClass, Method... methods)
    {
        // Build big detailed exception to help the developer
        StringBuilder err = new StringBuilder();
        err.append("Duplicate @");
        err.append(annoClass.getSimpleName());
        err.append(" declarations in: ");
        err.append(pojo.getName());

        for (Method method : methods)
        {
            err.append(System.lineSeparator());
            ReflectUtils.append(err, method);
        }

        return new DuplicateAnnotationException(err.toString());
    }

    public DuplicateAnnotationException(String message)
    {
        super(message);
    }

    public DuplicateAnnotationException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
