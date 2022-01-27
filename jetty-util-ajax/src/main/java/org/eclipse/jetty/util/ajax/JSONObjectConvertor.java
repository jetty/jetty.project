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

package org.eclipse.jetty.util.ajax;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.util.ajax.JSON.Output;

/**
 * Converts an Object to JSON using reflection on getters methods.
 */
public class JSONObjectConvertor implements JSON.Convertor
{
    private final boolean _fromJSON;
    private final Set<String> _excluded;

    public JSONObjectConvertor()
    {
        this(false);
    }

    public JSONObjectConvertor(boolean fromJSON)
    {
        this(fromJSON, null);
    }

    /**
     * @param fromJSON true to convert from JSON
     * @param excludedFieldNames An array of field names to exclude from the conversion
     */
    public JSONObjectConvertor(boolean fromJSON, String[] excludedFieldNames)
    {
        _fromJSON = fromJSON;
        _excluded = excludedFieldNames == null ? Set.of() : Set.of(excludedFieldNames);
    }

    @Override
    public Object fromJSON(Map<String, Object> map)
    {
        if (_fromJSON)
            throw new UnsupportedOperationException();
        return map;
    }

    @Override
    public void toJSON(Object obj, Output out)
    {
        try
        {
            Class<?> c = obj.getClass();

            if (_fromJSON)
                out.addClass(c);

            for (Method m : c.getMethods())
            {
                if (!Modifier.isStatic(m.getModifiers()) &&
                    m.getParameterCount() == 0 &&
                    m.getReturnType() != null &&
                    m.getDeclaringClass() != Object.class)
                {
                    String name = m.getName();
                    if (name.startsWith("is"))
                        name = name.substring(2, 3).toLowerCase(Locale.ENGLISH) + name.substring(3);
                    else if (name.startsWith("get"))
                        name = name.substring(3, 4).toLowerCase(Locale.ENGLISH) + name.substring(4);
                    else
                        continue;
                    if (includeField(name, obj, m))
                        out.add(name, m.invoke(obj, (Object[])null));
                }
            }
        }
        catch (Throwable e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    protected boolean includeField(String name, Object o, Method m)
    {
        return !_excluded.contains(name);
    }
}
