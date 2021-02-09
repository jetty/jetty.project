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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Primitives
{
    private static final Map<Class<?>, Class<?>> PRIMITIVE_CLASS_MAP;
    private static final Map<Class<?>, Class<?>> CLASS_PRIMITIVE_MAP;

    static
    {
        Map<Class<?>, Class<?>> primitives = new HashMap<>();

        // Map of classes to primitive types
        primitives.put(Boolean.class, Boolean.TYPE);
        primitives.put(Byte.class, Byte.TYPE);
        primitives.put(Character.class, Character.TYPE);
        primitives.put(Double.class, Double.TYPE);
        primitives.put(Float.class, Float.TYPE);
        primitives.put(Integer.class, Integer.TYPE);
        primitives.put(Long.class, Long.TYPE);
        primitives.put(Short.class, Short.TYPE);
        primitives.put(Void.class, Void.TYPE);

        CLASS_PRIMITIVE_MAP = Collections.unmodifiableMap(primitives);

        // Map of primitive types to classes
        Map<Class<?>, Class<?>> types = new HashMap<>();
        for (Map.Entry<Class<?>, Class<?>> classEntry : primitives.entrySet())
        {
            types.put(classEntry.getValue(), classEntry.getKey());
        }

        PRIMITIVE_CLASS_MAP = Collections.unmodifiableMap(types);
    }

    public static Class<?> getPrimitiveClass(Class<?> primitiveType)
    {
        return PRIMITIVE_CLASS_MAP.get(primitiveType);
    }

    public static Set<Class<?>> getPrimitiveClasses()
    {
        return CLASS_PRIMITIVE_MAP.keySet();
    }

    public static Set<Class<?>> getPrimitives()
    {
        return PRIMITIVE_CLASS_MAP.keySet();
    }

    public static Class<?> getPrimitiveType(Class<?> primitiveClass)
    {
        return CLASS_PRIMITIVE_MAP.get(primitiveClass);
    }
}
