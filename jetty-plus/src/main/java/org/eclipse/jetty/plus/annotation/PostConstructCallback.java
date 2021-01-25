//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.plus.annotation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * PostConstructCallback
 */
public class PostConstructCallback extends LifeCycleCallback
{

    /**
     * @param clazz the class object to be injected
     * @param methodName the name of the method to be injected
     */
    public PostConstructCallback(Class<?> clazz, String methodName)
    {
        super(clazz, methodName);
    }

    /**
     * @param className the name of the class to be injected
     * @param methodName the name of the method to be injected
     */
    public PostConstructCallback(String className, String methodName)
    {
        super(className, methodName);
    }

    /** 
     * Commons Annotation Specification section 2.5
     * - no params
     * - must be void return
     * - no checked exceptions
     * - cannot be static
     *
     * @see org.eclipse.jetty.plus.annotation.LifeCycleCallback#validate(java.lang.Class, java.lang.reflect.Method)
     */
    @Override
    public void validate(Class<?> clazz, Method method)
    {
        if (method.getExceptionTypes().length > 0)
            throw new IllegalArgumentException(clazz.getName() + "." + method.getName() + " cannot not throw a checked exception");

        if (!method.getReturnType().equals(Void.TYPE))
            throw new IllegalArgumentException(clazz.getName() + "." + method.getName() + " cannot not have a return type");

        if (Modifier.isStatic(method.getModifiers()))
            throw new IllegalArgumentException(clazz.getName() + "." + method.getName() + " cannot be static");
    }

    @Override
    public void callback(Object instance)
        throws SecurityException, IllegalArgumentException, NoSuchMethodException, ClassNotFoundException, IllegalAccessException, InvocationTargetException
    {
        super.callback(instance);
    }

    @Override
    public boolean equals(Object o)
    {
        return super.equals(o) && (o instanceof PostConstructCallback);
    }
}
