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

package org.eclipse.jetty.websocket.common.util;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.eclipse.jetty.websocket.core.InvalidWebSocketException;

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
            ReflectUtils.append(err,method);
        }

        return new DuplicateAnnotationException(err.toString());
    }

    public DuplicateAnnotationException(String message)
    {
        super(message);
    }

    public DuplicateAnnotationException(String message, Throwable cause)
    {
        super(message,cause);
    }
}
