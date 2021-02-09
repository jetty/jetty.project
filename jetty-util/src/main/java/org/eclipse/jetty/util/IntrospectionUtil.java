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

package org.eclipse.jetty.util;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/**
 * IntrospectionUtil
 */
public class IntrospectionUtil
{

    public static boolean isJavaBeanCompliantSetter(Method method)
    {
        if (method == null)
            return false;

        if (method.getReturnType() != Void.TYPE)
            return false;

        if (!method.getName().startsWith("set"))
            return false;

        return method.getParameterCount() == 1;
    }

    public static Method findMethod(Class<?> clazz, String methodName, Class<?>[] args, boolean checkInheritance, boolean strictArgs)
        throws NoSuchMethodException
    {
        if (clazz == null)
            throw new NoSuchMethodException("No class");
        if (methodName == null || methodName.trim().equals(""))
            throw new NoSuchMethodException("No method name");

        Method method = null;
        Method[] methods = clazz.getDeclaredMethods();
        for (int i = 0; i < methods.length && method == null; i++)
        {
            if (methods[i].getName().equals(methodName) && checkParams(methods[i].getParameterTypes(), (args == null ? new Class[]{} : args), strictArgs))
            {
                method = methods[i];
            }
        }
        if (method != null)
        {
            return method;
        }
        else if (checkInheritance)
            return findInheritedMethod(clazz.getPackage(), clazz.getSuperclass(), methodName, args, strictArgs);
        else
            throw new NoSuchMethodException("No such method " + methodName + " on class " + clazz.getName());
    }

    public static Field findField(Class<?> clazz, String targetName, Class<?> targetType, boolean checkInheritance, boolean strictType)
        throws NoSuchFieldException
    {
        if (clazz == null)
            throw new NoSuchFieldException("No class");
        if (targetName == null)
            throw new NoSuchFieldException("No field name");

        try
        {
            Field field = clazz.getDeclaredField(targetName);
            if (strictType)
            {
                if (field.getType().equals(targetType))
                    return field;
            }
            else
            {
                if (field.getType().isAssignableFrom(targetType))
                    return field;
            }
            if (checkInheritance)
            {
                return findInheritedField(clazz.getPackage(), clazz.getSuperclass(), targetName, targetType, strictType);
            }
            else
                throw new NoSuchFieldException("No field with name " + targetName + " in class " + clazz.getName() + " of type " + targetType);
        }
        catch (NoSuchFieldException e)
        {
            return findInheritedField(clazz.getPackage(), clazz.getSuperclass(), targetName, targetType, strictType);
        }
    }

    public static boolean isInheritable(Package pack, Member member)
    {
        if (pack == null)
            return false;
        if (member == null)
            return false;

        int modifiers = member.getModifiers();
        if (Modifier.isPublic(modifiers))
            return true;
        if (Modifier.isProtected(modifiers))
            return true;
        return !Modifier.isPrivate(modifiers) && pack.equals(member.getDeclaringClass().getPackage());
    }

    public static boolean checkParams(Class<?>[] formalParams, Class<?>[] actualParams, boolean strict)
    {
        if (formalParams == null)
            return actualParams == null;
        if (actualParams == null)
            return false;

        if (formalParams.length != actualParams.length)
            return false;

        if (formalParams.length == 0)
            return true;

        int j = 0;
        if (strict)
        {
            while (j < formalParams.length && formalParams[j].equals(actualParams[j]))
            {
                j++;
            }
        }
        else
        {
            while ((j < formalParams.length) && (formalParams[j].isAssignableFrom(actualParams[j])))
            {
                j++;
            }
        }

        return j == formalParams.length;
    }

    public static boolean isSameSignature(Method methodA, Method methodB)
    {
        if (methodA == null)
            return false;
        if (methodB == null)
            return false;

        List<Class<?>> parameterTypesA = Arrays.asList(methodA.getParameterTypes());
        List<Class<?>> parameterTypesB = Arrays.asList(methodB.getParameterTypes());

        return methodA.getName().equals(methodB.getName()) && parameterTypesA.containsAll(parameterTypesB);
    }

    public static boolean isTypeCompatible(Class<?> formalType, Class<?> actualType, boolean strict)
    {
        if (formalType == null)
            return actualType == null;
        if (actualType == null)
            return false;

        if (strict)
            return formalType.equals(actualType);
        else
            return formalType.isAssignableFrom(actualType);
    }

    public static boolean containsSameMethodSignature(Method method, Class<?> c, boolean checkPackage)
    {
        if (checkPackage)
        {
            if (!c.getPackage().equals(method.getDeclaringClass().getPackage()))
                return false;
        }

        boolean samesig = false;
        Method[] methods = c.getDeclaredMethods();
        for (int i = 0; i < methods.length && !samesig; i++)
        {
            if (IntrospectionUtil.isSameSignature(method, methods[i]))
                samesig = true;
        }
        return samesig;
    }

    public static boolean containsSameFieldName(Field field, Class<?> c, boolean checkPackage)
    {
        if (checkPackage)
        {
            if (!c.getPackage().equals(field.getDeclaringClass().getPackage()))
                return false;
        }

        boolean sameName = false;
        Field[] fields = c.getDeclaredFields();
        for (int i = 0; i < fields.length && !sameName; i++)
        {
            if (fields[i].getName().equals(field.getName()))
                sameName = true;
        }
        return sameName;
    }

    protected static Method findInheritedMethod(Package pack, Class<?> clazz, String methodName, Class<?>[] args, boolean strictArgs)
        throws NoSuchMethodException
    {
        if (clazz == null)
            throw new NoSuchMethodException("No class");
        if (methodName == null)
            throw new NoSuchMethodException("No method name");

        Method method = null;
        Method[] methods = clazz.getDeclaredMethods();
        for (int i = 0; i < methods.length && method == null; i++)
        {
            if (methods[i].getName().equals(methodName) &&
                isInheritable(pack, methods[i]) &&
                checkParams(methods[i].getParameterTypes(), args, strictArgs))
                method = methods[i];
        }
        if (method != null)
        {
            return method;
        }
        else
            return findInheritedMethod(clazz.getPackage(), clazz.getSuperclass(), methodName, args, strictArgs);
    }

    protected static Field findInheritedField(Package pack, Class<?> clazz, String fieldName, Class<?> fieldType, boolean strictType)
        throws NoSuchFieldException
    {
        if (clazz == null)
            throw new NoSuchFieldException("No class");
        if (fieldName == null)
            throw new NoSuchFieldException("No field name");
        try
        {
            Field field = clazz.getDeclaredField(fieldName);
            if (isInheritable(pack, field) && isTypeCompatible(fieldType, field.getType(), strictType))
                return field;
            else
                return findInheritedField(clazz.getPackage(), clazz.getSuperclass(), fieldName, fieldType, strictType);
        }
        catch (NoSuchFieldException e)
        {
            return findInheritedField(clazz.getPackage(), clazz.getSuperclass(), fieldName, fieldType, strictType);
        }
    }
}
