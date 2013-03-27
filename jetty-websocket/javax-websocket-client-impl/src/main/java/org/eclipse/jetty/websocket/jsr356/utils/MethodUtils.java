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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;

public final class MethodUtils
{
    private static StringBuilder appendTypeName(StringBuilder sb, Type type, boolean ellipses)
    {
        if (type instanceof Class<?>)
        {
            Class<?> ctype = (Class<?>)type;
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

    public static String toString(Class<?> pojo, Method method)
    {
        StringBuilder str = new StringBuilder();
        str.append(pojo.getName());
        str.append("  ");
        // method modifiers
        int mod = method.getModifiers() & Modifier.methodModifiers();
        if (mod != 0)
        {
            str.append(Modifier.toString(mod)).append(' ');
        }

        // return type
        Type retType = method.getGenericReturnType();
        appendTypeName(str,retType,false).append(' ');

        // method name
        str.append(method.getName());

        // method parameters
        str.append('(');
        Type[] params = method.getGenericParameterTypes();
        for (int j = 0; j < params.length; j++)
        {
            boolean ellipses = method.isVarArgs() && (j == (params.length - 1));
            appendTypeName(str,params[j],ellipses);
            if (j < (params.length - 1))
            {
                str.append(", ");
            }
        }
        str.append(')');

        // TODO: show exceptions?
        return str.toString();
    }
}
