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

package org.eclipse.jetty.websocket.common.events.annotated;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.events.ParamList;

/**
 * Basic scanner for Annotated Methods
 * @param <T> The type of metadata
 */
public abstract class AbstractMethodAnnotationScanner<T>
{
    protected void assertIsPublicNonStatic(Method method)
    {
        int mods = method.getModifiers();
        if (!Modifier.isPublic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid declaration of ");
            err.append(method);
            err.append(System.lineSeparator());

            err.append("Method modifier must be public");

            throw new InvalidWebSocketException(err.toString());
        }

        if (Modifier.isStatic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid declaration of ");
            err.append(method);
            err.append(System.lineSeparator());

            err.append("Method modifier may not be static");

            throw new InvalidWebSocketException(err.toString());
        }
    }

    protected void assertIsReturn(Method method, Class<?> type)
    {
        if (!type.equals(method.getReturnType()))
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid declaration of ");
            err.append(method);
            err.append(System.lineSeparator());

            err.append("Return type must be ").append(type);

            throw new InvalidWebSocketException(err.toString());
        }
    }

    protected void assertIsVoidReturn(Method method)
    {
        assertIsReturn(method,Void.TYPE);
    }

    protected void assertUnset(CallableMethod callable, Class<? extends Annotation> annoClass, Method method)
    {
        if (callable != null)
        {
            // Attempt to add duplicate frame type (a no-no)
            StringBuilder err = new StringBuilder();
            err.append("Duplicate @").append(annoClass.getSimpleName()).append(" declaration on ");
            err.append(method);
            err.append(System.lineSeparator());

            err.append("@").append(annoClass.getSimpleName()).append(" previously declared at ");
            err.append(callable.getMethod());

            throw new InvalidWebSocketException(err.toString());
        }
    }

    protected void assertValidSignature(Method method, Class<? extends Annotation> annoClass, ParamList validParams)
    {
        assertIsPublicNonStatic(method);
        assertIsReturn(method,Void.TYPE);

        boolean valid = false;

        // validate parameters
        Class<?> actual[] = method.getParameterTypes();
        for (Class<?>[] params : validParams)
        {
            if (isSameParameters(actual,params))
            {
                valid = true;
                break;
            }
        }

        if (!valid)
        {
            throw InvalidSignatureException.build(method,annoClass,validParams);
        }
    }

    public boolean isAnnotation(Annotation annotation, Class<? extends Annotation> annotationClass)
    {
        return annotation.annotationType().equals(annotationClass);
    }

    public boolean isSameParameters(Class<?>[] actual, Class<?>[] params)
    {
        if (actual.length != params.length)
        {
            // skip
            return false;
        }

        int len = params.length;
        for (int i = 0; i < len; i++)
        {
            if (!actual[i].equals(params[i]))
            {
                return false; // not valid
            }
        }

        return true;
    }

    protected boolean isSignatureMatch(Method method, ParamList validParams)
    {
        assertIsPublicNonStatic(method);
        assertIsReturn(method,Void.TYPE);

        // validate parameters
        Class<?> actual[] = method.getParameterTypes();
        for (Class<?>[] params : validParams)
        {
            if (isSameParameters(actual,params))
            {
                return true;
            }
        }

        return false;
    }

    protected boolean isTypeAnnotated(Class<?> pojo, Class<? extends Annotation> expectedAnnotation)
    {
        return pojo.getAnnotation(expectedAnnotation) != null;
    }

    public abstract void onMethodAnnotation(T metadata, Class<?> pojo, Method method, Annotation annotation);

    public void scanMethodAnnotations(T metadata, Class<?> pojo)
    {
        Class<?> clazz = pojo;

        while ((clazz != null) && Object.class.isAssignableFrom(clazz))
        {
            for (Method method : clazz.getDeclaredMethods())
            {
                Annotation annotations[] = method.getAnnotations();
                if ((annotations == null) || (annotations.length <= 0))
                {
                    continue; // skip
                }
                for (Annotation annotation : annotations)
                {
                    onMethodAnnotation(metadata,clazz,method,annotation);
                }
            }

            clazz = clazz.getSuperclass();
        }
    }
}
