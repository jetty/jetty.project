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
import org.eclipse.jetty.util.ajax.JSON.Convertor;
import org.eclipse.jetty.util.ajax.JSON.Output;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JSONPojoConvertorFactory implements JSON.Convertor
{
    private static final Logger LOG = LoggerFactory.getLogger(JSONPojoConvertorFactory.class);
    private final JSON _json;
    private final boolean _fromJson;

    public JSONPojoConvertorFactory(JSON json)
    {
        this(json, true);
    }

    /**
     * @param json The JSON instance to use
     * @param fromJSON If true, the class name of the objects is included
     * in the generated JSON and is used to instantiate the object when
     * JSON is parsed (otherwise a Map is used).
     */
    public JSONPojoConvertorFactory(JSON json, boolean fromJSON)
    {
        if (json == null)
            throw new IllegalArgumentException();
        _json = json;
        _fromJson = fromJSON;
    }

    @Override
    public void toJSON(Object obj, Output out)
    {
        Class<?> cls = obj.getClass();
        String clsName = cls.getName();
        Convertor convertor = _json.getConvertorFor(clsName);
        if (convertor == null)
        {
            convertor = new JSONPojoConvertor(cls, _fromJson);
            _json.addConvertorFor(clsName, convertor);
        }
        convertor.toJSON(obj, out);
    }

    @Override
    public Object fromJSON(Map<String, Object> map)
    {
        String clsName = (String)map.get("class");
        if (clsName != null)
        {
            Convertor convertor = _json.getConvertorFor(clsName);
            if (convertor == null)
            {
                try
                {
                    Class<?> cls = Loader.loadClass(clsName);
                    convertor = new JSONPojoConvertor(cls, _fromJson);
                    _json.addConvertorFor(clsName, convertor);
                }
                catch (ClassNotFoundException e)
                {
                    LOG.warn("Unable to find class: {}", clsName, e);
                }
            }
            if (convertor != null)
                return convertor.fromJSON(map);
        }
        return map;
    }
}
