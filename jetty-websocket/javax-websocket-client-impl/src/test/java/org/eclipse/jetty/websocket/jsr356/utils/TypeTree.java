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

package org.eclipse.jetty.websocket.jsr356.utils;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;

import org.eclipse.jetty.websocket.common.util.ReflectUtils;

public class TypeTree
{
    public static void dumpTree(String indent, Type type)
    {
        if ((type == null) || (type == Object.class))
        {
            return;
        }

        if (type instanceof Class<?>)
        {
            Class<?> ctype = (Class<?>)type;
            System.out.printf("%s (Class) = %s%n", indent, ctype.getName());

            String name = ctype.getName();
            if (name.startsWith("java.lang.") || name.startsWith("java.io."))
            {
                // filter away standard classes from tree (otherwise it will go on infinitely)
                return;
            }

            Type superType = ctype.getGenericSuperclass();
            dumpTree(indent + ".genericSuperClass()", superType);

            Type[] ifaces = ctype.getGenericInterfaces();
            if ((ifaces != null) && (ifaces.length > 0))
            {
                // System.out.printf("%s.genericInterfaces[].length = %d%n",indent,ifaces.length);
                for (int i = 0; i < ifaces.length; i++)
                {
                    Type iface = ifaces[i];
                    dumpTree(indent + ".genericInterfaces[" + i + "]", iface);
                }
            }

            TypeVariable<?>[] typeParams = ctype.getTypeParameters();
            if ((typeParams != null) && (typeParams.length > 0))
            {
                // System.out.printf("%s.typeParameters[].length = %d%n",indent,typeParams.length);
                for (int i = 0; i < typeParams.length; i++)
                {
                    TypeVariable<?> typeParam = typeParams[i];
                    dumpTree(indent + ".typeParameters[" + i + "]", typeParam);
                }
            }
            return;
        }

        if (type instanceof ParameterizedType)
        {
            ParameterizedType ptype = (ParameterizedType)type;
            System.out.printf("%s (ParameterizedType) = %s%n", indent, ReflectUtils.toShortName(ptype));
            // dumpTree(indent + ".ownerType()",ptype.getOwnerType());
            dumpTree(indent + ".rawType(" + ReflectUtils.toShortName(ptype.getRawType()) + ")", ptype.getRawType());
            Type[] args = ptype.getActualTypeArguments();
            if (args != null)
            {
                System.out.printf("%s.actualTypeArguments[].length = %d%n", indent, args.length);
                for (int i = 0; i < args.length; i++)
                {
                    Type arg = args[i];
                    dumpTree(indent + ".actualTypeArguments[" + i + "]", arg);
                }
            }
            return;
        }

        if (type instanceof GenericArrayType)
        {
            GenericArrayType gtype = (GenericArrayType)type;
            System.out.printf("%s (GenericArrayType) = %s%n", indent, gtype);
            return;
        }

        if (type instanceof TypeVariable<?>)
        {
            TypeVariable<?> tvar = (TypeVariable<?>)type;
            System.out.printf("%s (TypeVariable) = %s%n", indent, tvar);
            System.out.printf("%s.getName() = %s%n", indent, tvar.getName());
            System.out.printf("%s.getGenericDeclaration() = %s%n", indent, tvar.getGenericDeclaration());
            return;
        }

        if (type instanceof WildcardType)
        {
            System.out.printf("%s (WildcardType) = %s%n", indent, type);
            return;
        }

        System.out.printf("%s (?) = %s%n", indent, type);
    }
}
