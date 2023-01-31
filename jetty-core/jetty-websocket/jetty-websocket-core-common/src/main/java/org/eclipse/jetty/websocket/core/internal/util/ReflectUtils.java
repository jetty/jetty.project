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

package org.eclipse.jetty.websocket.core.internal.util;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jetty.websocket.core.exception.DuplicateAnnotationException;

public class ReflectUtils
{
    private static final Pattern JAVAX_CLASSNAME_PATTERN = Pattern.compile("^javax*\\..*");
    private static final Pattern JAKARTA_CLASSNAME_PATTERN = Pattern.compile("^jakarta*\\..*");

    private static class GenericRef
    {
        // The base class reference lookup started from
        private final Class<?> baseClass;
        // The interface that we are interested in
        private final Class<?> ifaceClass;

        // The actual class generic interface was found on
        Class<?> genericClass;

        // The found genericType
        public Type genericType;
        private int genericIndex;

        public GenericRef(final Class<?> baseClass, final Class<?> ifaceClass)
        {
            this.baseClass = baseClass;
            this.ifaceClass = ifaceClass;
        }

        public boolean needsUnwrap()
        {
            return (genericClass == null) && (genericType != null) && (genericType instanceof TypeVariable<?>);
        }

        public void setGenericFromType(Type type, int index)
        {
            this.genericType = type;
            this.genericIndex = index;
            if (type instanceof Class)
            {
                this.genericClass = (Class<?>)type;
            }
        }

        @Override
        public String toString()
        {
            return "GenericRef [baseClass=" + baseClass +
                ", ifaceClass=" + ifaceClass +
                ", genericType=" + genericType +
                ", genericClass=" + genericClass +
                "]";
        }
    }

    private static StringBuilder appendTypeName(StringBuilder sb, Type type, boolean ellipses)
    {
        if (type instanceof Class<?> ctype)
        {
            if (ctype.isArray())
            {
                try
                {
                    int dimensions = 0;
                    while (ctype.isArray())
                    {
                        dimensions++;
                        ctype = ctype.getComponentType();
                    }
                    sb.append(ctype.getName());
                    for (int i = 0; i < dimensions; i++)
                    {
                        if (ellipses)
                        {
                            sb.append("...");
                        }
                        else
                        {
                            sb.append("[]");
                        }
                    }
                    return sb;
                }
                catch (Throwable ignore)
                {
                    // ignore
                }
            }

            sb.append(ctype.getName());
        }
        else
        {
            sb.append(type.toString());
        }

        return sb;
    }

    public static Method findMethod(Class<?> pojo, String methodName, Class<?>... params)
    {
        try
        {
            return pojo.getMethod(methodName, params);
        }
        catch (NoSuchMethodException e)
        {
            return null;
        }
    }

    public static Method findAnnotatedMethod(Class<?> pojo, Class<? extends Annotation> anno)
    {
        Method[] methods = findAnnotatedMethods(pojo, anno);
        if (methods == null)
        {
            return null;
        }

        if (methods.length > 1)
        {
            throw DuplicateAnnotationException.build(pojo, anno, methods);
        }

        return methods[0];
    }

    public static Method[] findAnnotatedMethods(Class<?> pojo, Class<? extends Annotation> anno)
    {
        Class<?> clazz = pojo;
        List<Method> methods = new ArrayList<>();
        while ((clazz != null) && Object.class.isAssignableFrom(clazz))
        {
            Stream.of(clazz.getDeclaredMethods())
                .filter(method -> !method.isSynthetic() && (method.getAnnotation(anno) != null))
                .forEach(methods::add);
            clazz = clazz.getSuperclass();
        }

        if (methods.isEmpty())
            return null;
        return methods.toArray(new Method[0]);
    }

    /**
     * Given a Base (concrete) Class, find the interface specified, and return its concrete Generic class declaration.
     *
     * @param baseClass the base (concrete) class to look in
     * @param ifaceClass the interface of interest
     * @return the (concrete) generic class that the interface exposes
     */
    public static Class<?> findGenericClassFor(Class<?> baseClass, Class<?> ifaceClass)
    {
        GenericRef ref = new GenericRef(baseClass, ifaceClass);
        if (resolveGenericRef(ref, baseClass))
            return ref.genericClass;

        return null;
    }

    private static int findTypeParameterIndex(Class<?> clazz, TypeVariable<?> needVar)
    {
        TypeVariable<?>[] params = clazz.getTypeParameters();
        for (int i = 0; i < params.length; i++)
        {
            if (params[i].getName().equals(needVar.getName()))
                return i;
        }

        return -1;
    }

    public static boolean isDefaultConstructable(Class<?> clazz)
    {
        int mods = clazz.getModifiers();
        if (Modifier.isAbstract(mods) || !Modifier.isPublic(mods))
        {
            // Needs to be public, non-abstract
            return false;
        }

        Class<?>[] noargs = new Class<?>[0];
        try
        {
            // Needs to have a no-args constructor
            Constructor<?> constructor = clazz.getConstructor(noargs);
            // Constructor needs to be public
            return Modifier.isPublic(constructor.getModifiers());
        }
        catch (NoSuchMethodException | SecurityException e)
        {
            return false;
        }
    }

    private static boolean resolveGenericRef(GenericRef ref, Class<?> clazz, Type type)
    {
        if (type instanceof Class)
        {
            if (type == ref.ifaceClass)
            {
                // is this a straight ref or a TypeVariable?
                ref.setGenericFromType(type, 0);
                return true;
            }
            else
            {
                // Keep digging
                return resolveGenericRef(ref, type);
            }
        }

        if (type instanceof ParameterizedType)
        {
            ParameterizedType ptype = (ParameterizedType)type;
            Type rawType = ptype.getRawType();
            if (rawType == ref.ifaceClass)
            {
                // Always get the raw type parameter, let unwrap() solve for what it is
                ref.setGenericFromType(ptype.getActualTypeArguments()[0], 0);
                return true;
            }
            else
            {
                // Keep digging
                return resolveGenericRef(ref, rawType);
            }
        }
        return false;
    }

    private static boolean resolveGenericRef(GenericRef ref, Type type)
    {
        if ((type == null) || (type == Object.class))
        {
            return false;
        }

        if (type instanceof Class<?> clazz)
        {
            // Prevent spinning off into Serialization and other parts of the standard tree that we couldn't care less about.
            if (JAKARTA_CLASSNAME_PATTERN.matcher(clazz.getName()).matches() || JAVAX_CLASSNAME_PATTERN.matcher(clazz.getName()).matches())
            {
                return false;
            }

            Type[] ifaces = clazz.getGenericInterfaces();
            for (Type iface : ifaces)
            {
                if (resolveGenericRef(ref, clazz, iface))
                {
                    if (ref.needsUnwrap())
                    {
                        TypeVariable<?> needVar = (TypeVariable<?>)ref.genericType;

                        // attempt to find typeParameter on class itself
                        int typeParamIdx = findTypeParameterIndex(clazz, needVar);
                        if (typeParamIdx >= 0)
                        {
                            // found a type parameter, use it
                            TypeVariable<?>[] params = clazz.getTypeParameters();
                            if (params.length >= typeParamIdx)
                            {
                                ref.setGenericFromType(params[typeParamIdx], typeParamIdx);
                            }
                        }
                        else if (iface instanceof ParameterizedType)
                        {
                            // use actual args on interface
                            Type arg = ((ParameterizedType)iface).getActualTypeArguments()[ref.genericIndex];
                            ref.setGenericFromType(arg, ref.genericIndex);
                        }
                    }
                    return true;
                }
            }

            type = clazz.getGenericSuperclass();
            return resolveGenericRef(ref, type);
        }

        if (type instanceof ParameterizedType ptype)
        {
            Class<?> rawClass = (Class<?>)ptype.getRawType();
            if (resolveGenericRef(ref, rawClass))
            {
                if (ref.needsUnwrap())
                {
                    TypeVariable<?> needVar = (TypeVariable<?>)ref.genericType;
                    int typeParamIdx = findTypeParameterIndex(rawClass, needVar);

                    Type arg = ptype.getActualTypeArguments()[typeParamIdx];
                    ref.setGenericFromType(arg, typeParamIdx);
                    return true;
                }
            }
        }

        return false;
    }

    public static String toString(Class<?> pojo, Method method)
    {
        StringBuilder str = new StringBuilder();
        append(str, pojo, method);
        return str.toString();
    }

    public static void append(StringBuilder str, Class<?> pojo, Method method)
    {
        // method modifiers
        int mod = method.getModifiers() & Modifier.methodModifiers();
        if (mod != 0)
            str.append(Modifier.toString(mod)).append(' ');

        // return type
        Type retType = method.getGenericReturnType();
        appendTypeName(str, retType, false).append(' ');

        if (pojo != null)
        {
            // class name
            str.append(pojo.getName());
            str.append("#");
        }

        // method name
        str.append(method.getName());

        // method parameters
        str.append('(');
        Type[] params = method.getGenericParameterTypes();
        for (int j = 0; j < params.length; j++)
        {
            boolean ellipses = method.isVarArgs() && (j == (params.length - 1));
            appendTypeName(str, params[j], ellipses);
            if (j < (params.length - 1))
            {
                str.append(", ");
            }
        }
        str.append(')');
    }

    public static void append(StringBuilder str, Method method)
    {
        append(str, null, method);
    }

    public static void append(StringBuilder str, MethodType methodType)
    {
        str.append(methodType.returnType().getName());
        str.append("(");
        boolean delim = false;
        for (Class<?> paramType : methodType.parameterList())
        {
            if (delim)
                str.append(", ");
            str.append(paramType.getName());
            delim = true;
        }
        str.append(")");
    }
}
