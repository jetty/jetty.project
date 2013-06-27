//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.utils;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

public class ReflectUtils
{
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

        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder();
            builder.append("GenericRef [baseClass=");
            builder.append(baseClass);
            builder.append(", ifaceClass=");
            builder.append(ifaceClass);
            builder.append(", genericType=");
            builder.append(genericType);
            builder.append(", genericClass=");
            builder.append(genericClass);
            builder.append("]");
            return builder.toString();
        }

        public void setGenericFromType(Type type, int index)
        {
            // debug("setGenericFromType(%s,%d)",toShortName(type),index);
            this.genericType = type;
            this.genericIndex = index;
            if (type instanceof Class)
            {
                this.genericClass = (Class<?>)type;
            }
        }
    }

    /**
     * Given a Base (concrete) Class, find the interface specified, and return its concrete Generic class declaration.
     * 
     * @param baseClass
     *            the base (concrete) class to look in
     * @param ifaceClass
     *            the interface of interest
     * @return the (concrete) generic class that the interface exposes
     */
    public static Class<?> findGenericClassFor(Class<?> baseClass, Class<?> ifaceClass)
    {
        GenericRef ref = new GenericRef(baseClass,ifaceClass);
        if (resolveGenericRef(ref,baseClass))
        {
            // debug("Generic Found: %s",ref.genericClass);
            return ref.genericClass;
        }

        // debug("Generic not found: %s",ref);
        return null;
    }

    private static boolean resolveGenericRef(GenericRef ref, Type type)
    {
        if ((type == null) || (type == Object.class))
        {
            return false;
        }

        if (type instanceof Class)
        {
            Class<?> clazz = (Class<?>)type;
            // prevent spinning off into Serialization and other parts of the
            // standard tree that we could care less about
            if (clazz.getName().matches("^javax*\\..*"))
            {
                return false;
            }

            Type ifaces[] = clazz.getGenericInterfaces();
            for (Type iface : ifaces)
            {
                // debug("resolve %s interface[]: %s",toShortName(clazz),toShortName(iface));
                if (resolveGenericRef(ref,clazz,iface))
                {
                    if (ref.needsUnwrap())
                    {
                        // debug("## Unwrap class %s::%s",toShortName(clazz),toShortName(iface));
                        TypeVariable<?> needVar = (TypeVariable<?>)ref.genericType;
                        // debug("needs unwrap of type var [%s] - index [%d]",toShortName(needVar),ref.genericIndex);

                        // attempt to find typeParameter on class itself
                        int typeParamIdx = findTypeParameterIndex(clazz,needVar);
                        // debug("type param index for %s[%s] is [%d]",toShortName(clazz),toShortName(needVar),typeParamIdx);

                        if (typeParamIdx >= 0)
                        {
                            // found a type parameter, use it
                            // debug("unwrap from class [%s] - typeParameters[%d]",toShortName(clazz),typeParamIdx);
                            TypeVariable<?> params[] = clazz.getTypeParameters();
                            if (params.length >= typeParamIdx)
                            {
                                ref.setGenericFromType(params[typeParamIdx],typeParamIdx);
                            }
                        }
                        else if (iface instanceof ParameterizedType)
                        {
                            // use actual args on interface
                            Type arg = ((ParameterizedType)iface).getActualTypeArguments()[ref.genericIndex];
                            ref.setGenericFromType(arg,ref.genericIndex);
                        }
                    }
                    return true;
                }
            }

            type = clazz.getGenericSuperclass();
            return resolveGenericRef(ref,type);
        }

        if (type instanceof ParameterizedType)
        {
            ParameterizedType ptype = (ParameterizedType)type;
            Class<?> rawClass = (Class<?>)ptype.getRawType();
            if (resolveGenericRef(ref,rawClass))
            {
                if (ref.needsUnwrap())
                {
                    // debug("## Unwrap ParameterizedType %s::%s",toShortName(type),toShortName(rawClass));
                    TypeVariable<?> needVar = (TypeVariable<?>)ref.genericType;
                    // debug("needs unwrap of type var [%s] - index [%d]",toShortName(needVar),ref.genericIndex);
                    int typeParamIdx = findTypeParameterIndex(rawClass,needVar);
                    // debug("type paramIdx of %s::%s is index [%d]",toShortName(rawClass),toShortName(needVar),typeParamIdx);

                    Type arg = ptype.getActualTypeArguments()[typeParamIdx];
                    ref.setGenericFromType(arg,typeParamIdx);
                    return true;
                }
            }
        }

        return false;
    }

    private static int findTypeParameterIndex(Class<?> clazz, TypeVariable<?> needVar)
    {
        // debug("findTypeParameterIndex(%s, [%s])",toShortName(clazz),toShortName(needVar));
        TypeVariable<?> params[] = clazz.getTypeParameters();
        for (int i = 0; i < params.length; i++)
        {
            if (params[i].getName().equals(needVar.getName()))
            {
                // debug("Type Parameter found at index: [%d]",i);
                return i;
            }
        }
        // debug("Type Parameter NOT found");
        return -1;
    }

    private static boolean resolveGenericRef(GenericRef ref, Class<?> clazz, Type type)
    {
        if (type instanceof Class)
        {
            if (type == ref.ifaceClass)
            {
                // is this a straight ref or a TypeVariable?
                // debug("Found ref (as class): %s",toShortName(type));
                ref.setGenericFromType(type,0);
                return true;
            }
            else
            {
                // Keep digging
                return resolveGenericRef(ref,(Class<?>)type);
            }
        }

        if (type instanceof ParameterizedType)
        {
            ParameterizedType ptype = (ParameterizedType)type;
            Type rawType = ptype.getRawType();
            if (rawType == ref.ifaceClass)
            {
                // debug("Found ref on [%s] as ParameterizedType [%s]",toShortName(clazz),toShortName(ptype));
                // Always get the raw type parameter, let unwrap() solve for what it is
                ref.setGenericFromType(ptype.getActualTypeArguments()[0],0);
                return true;
            }
            else
            {
                // Keep digging
                return resolveGenericRef(ref,(Class<?>)rawType);
            }
        }
        return false;
    }

    public static String trimClassName(String name)
    {
        int idx = name.lastIndexOf('.');
        name = name.substring(idx + 1);
        idx = name.lastIndexOf('$');
        if (idx >= 0)
        {
            name = name.substring(idx + 1);
        }
        return name;
    }

    public static String toShortName(Type type)
    {
        if (type == null)
        {
            return "<null>";
        }

        if (type instanceof Class)
        {
            String name = ((Class<?>)type).getName();
            return trimClassName(name);
        }

        if (type instanceof ParameterizedType)
        {
            ParameterizedType ptype = (ParameterizedType)type;
            StringBuilder str = new StringBuilder();
            str.append(trimClassName(((Class<?>)ptype.getRawType()).getName()));
            str.append("<");
            Type args[] = ptype.getActualTypeArguments();
            for (int i = 0; i < args.length; i++)
            {
                if (i > 0)
                    str.append(",");
                str.append(args[i]);
            }
            str.append(">");
            return str.toString();
        }

        return type.toString();
    }
}
