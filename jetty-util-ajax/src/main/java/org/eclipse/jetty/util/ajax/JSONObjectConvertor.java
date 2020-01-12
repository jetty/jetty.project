//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.ajax;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.util.ajax.JSON.Output;

/**
 * Convert an Object to JSON using reflection on getters methods.
 */
public class JSONObjectConvertor implements JSON.Convertor
{
    private boolean _fromJSON;
    private Set _excluded = null;

    public JSONObjectConvertor()
    {
        _fromJSON = false;
    }

    public JSONObjectConvertor(boolean fromJSON)
    {
        _fromJSON = fromJSON;
    }

    /**
     * @param fromJSON true to convert from JSON
     * @param excluded An array of field names to exclude from the conversion
     */
    public JSONObjectConvertor(boolean fromJSON, String[] excluded)
    {
        _fromJSON = fromJSON;
        if (excluded != null)
            _excluded = new HashSet(Arrays.asList(excluded));
    }

    @Override
    public Object fromJSON(Map map)
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
            Class c = obj.getClass();

            if (_fromJSON)
                out.addClass(obj.getClass());

            Method[] methods = obj.getClass().getMethods();

            for (int i = 0; i < methods.length; i++)
            {
                Method m = methods[i];
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
        return _excluded == null || !_excluded.contains(name);
    }
}
