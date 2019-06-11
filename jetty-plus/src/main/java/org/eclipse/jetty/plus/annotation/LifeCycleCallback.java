//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import java.util.Objects;

import org.eclipse.jetty.util.IntrospectionUtil;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.TypeUtil;



/**
 * LifeCycleCallback
 * 
 * Holds information about a class and method
 * that has either been configured in web.xml to have postconstruct or
 * predestroy callbacks, or has the equivalent annotations.
 */
public abstract class LifeCycleCallback
{
    public static final Object[] __EMPTY_ARGS = new Object[] {};
    private Method _target;
    private Class<?> _targetClass;
    private String _className;
    private String _methodName;


    public LifeCycleCallback()
    {
    }

    /**
     * @return the _targetClass
     */
    public Class<?> getTargetClass()
    {
        return _targetClass;
    }

    public String getTargetClassName()
    {
        return _className;
    }

    public String getMethodName()
    {
        return _methodName;
    }

    /**
     * @return the target
     */
    public Method getTarget()
    {
        return _target;
    }


    public void setTarget (String className, String methodName)
    {
        Objects.requireNonNull(className);
        Objects.requireNonNull(methodName);
        _className = className;
        _methodName = methodName;
    }

    public void setTarget (Class<?> clazz, String methodName)
    {
        Objects.requireNonNull(clazz);
        Objects.requireNonNull(methodName);
        try
        {
            Method method = IntrospectionUtil.findMethod(clazz, methodName, null, true, true);
            validate(clazz, method);
            _target = method;
            _targetClass = clazz;
            _className = clazz.getCanonicalName();
            _methodName = methodName;
        }
        catch (NoSuchMethodException e)
        {
            throw new IllegalArgumentException ("Method "+methodName+" not found on class "+clazz.getName());
        }
    }




    public void callback (Object instance)
    throws SecurityException, NoSuchMethodException, ClassNotFoundException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
    {
        if (_target == null)
        {
            if (_targetClass == null)
                _targetClass = Loader.loadClass(_className);
            _target = _targetClass.getDeclaredMethod(_methodName, TypeUtil.NO_ARGS);
        }

        if (_target != null)
        {
            boolean accessibility = getTarget().isAccessible();
            getTarget().setAccessible(true);
            getTarget().invoke(instance, __EMPTY_ARGS);
            getTarget().setAccessible(accessibility);
        }
    }



    /**
     * Find a method of the given name either directly in the given
     * class, or inherited.
     *
     * @param pack the package of the class under inspection
     * @param clazz the class under inspection
     * @param methodName the method to find
     * @param checkInheritance false on first entry, true if a superclass is being introspected
     * @return the method
     */
    public Method findMethod (Package pack, Class<?> clazz, String methodName, boolean checkInheritance)
    {
        if (clazz == null)
            return null;

        try
        {
            Method method = clazz.getDeclaredMethod(methodName);
            if (checkInheritance)
            {
                int modifiers = method.getModifiers();
                if (Modifier.isProtected(modifiers) || Modifier.isPublic(modifiers) || (!Modifier.isPrivate(modifiers)&&(pack.equals(clazz.getPackage()))))
                    return method;
                else
                    return findMethod(clazz.getPackage(), clazz.getSuperclass(), methodName, true);
            }
            return method;
        }
        catch (NoSuchMethodException e)
        {
            return findMethod(clazz.getPackage(), clazz.getSuperclass(), methodName, true);
        }
    }

    
    @Override
    public int hashCode()
    {
        int hash = 0;
        if (_className != null)
            hash = _className.hashCode();
        if (_methodName != null)
            hash ^= _methodName.hashCode();
        return hash;
    }


    @Override
    public boolean equals (Object o)
    {
        if (o==null)
            return false;

        if (!LifeCycleCallback.class.isInstance(o))
            return false;

        LifeCycleCallback callback = (LifeCycleCallback)o;
        
        if (this == callback)
            return true;

        if (getTargetClassName() == null)
            return (getMethodName() == null && callback.getTargetClassName() == null && callback.getMethodName() == null);

        if (getTargetClassName().equals(callback.getTargetClassName()))
            return ((getMethodName() == null && callback.getMethodName() == null)
                || (getMethodName().equals(callback.getMethodName())));

        return false;
    }

    public abstract void validate (Class<?> clazz, Method m);
}
