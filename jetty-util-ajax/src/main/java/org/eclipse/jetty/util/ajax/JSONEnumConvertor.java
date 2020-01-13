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
import java.util.Map;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.ajax.JSON.Output;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Convert an {@link Enum} to JSON.
 * If fromJSON is true in the constructor, the JSON generated will
 * be of the form {class="com.acme.TrafficLight",value="Green"}
 * If fromJSON is false, then only the string value of the enum is generated.
 */
public class JSONEnumConvertor implements JSON.Convertor
{
    private static final Logger LOG = Log.getLogger(JSONEnumConvertor.class);
    private boolean _fromJSON;
    private Method _valueOf;

    {
        try
        {
            Class<?> e = Loader.loadClass("java.lang.Enum");
            _valueOf = e.getMethod("valueOf", Class.class, String.class);
        }
        catch (Exception e)
        {
            throw new RuntimeException("!Enums", e);
        }
    }

    public JSONEnumConvertor()
    {
        this(false);
    }

    public JSONEnumConvertor(boolean fromJSON)
    {
        _fromJSON = fromJSON;
    }

    @Override
    public Object fromJSON(Map map)
    {
        if (!_fromJSON)
            throw new UnsupportedOperationException();
        try
        {
            Class c = Loader.loadClass((String)map.get("class"));
            return _valueOf.invoke(null, c, map.get("value"));
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
        return null;
    }

    @Override
    public void toJSON(Object obj, Output out)
    {
        if (_fromJSON)
        {
            out.addClass(obj.getClass());
            out.add("value", ((Enum)obj).name());
        }
        else
        {
            out.add(((Enum)obj).name());
        }
    }
}
