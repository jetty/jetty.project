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
        if (super.equals(o) && (o instanceof PostConstructCallback))
            return true;
        return false;
    }
}
