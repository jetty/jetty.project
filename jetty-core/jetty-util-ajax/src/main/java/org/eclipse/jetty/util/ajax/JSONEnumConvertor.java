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

import java.util.Map;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.ajax.JSON.Output;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Convert an {@link Enum} to JSON.
 * If fromJSON is true in the constructor, the JSON generated will
 * be of the form {class="com.acme.TrafficLight",value="Green"}
 * If fromJSON is false, then only the string value of the enum is generated.
 */
public class JSONEnumConvertor implements JSON.Convertor
{
    private static final Logger LOG = LoggerFactory.getLogger(JSONEnumConvertor.class);
    private boolean _fromJSON;

    public JSONEnumConvertor()
    {
        this(false);
    }

    public JSONEnumConvertor(boolean fromJSON)
    {
        _fromJSON = fromJSON;
    }

    @Override
    public Object fromJSON(Map<String, Object> map)
    {
        if (!_fromJSON)
            throw new UnsupportedOperationException();

        String clazzname = (String)map.get("class");
        try
        {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Class<? extends Enum> type = Loader.loadClass(clazzname);
            return Enum.valueOf(type, (String)map.get("value"));
        }
        catch (Exception e)
        {
            LOG.warn("Unable to load class: {}", clazzname, e);
            return null;
        }
    }

    @Override
    public void toJSON(Object obj, Output out)
    {
        if (_fromJSON)
        {
            out.addClass(obj.getClass());
            out.add("value", ((Enum<?>)obj).name());
        }
        else
        {
            out.add(((Enum<?>)obj).name());
        }
    }
}
