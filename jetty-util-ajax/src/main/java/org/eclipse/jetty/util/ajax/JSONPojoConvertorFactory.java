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

package org.eclipse.jetty.util.ajax;

import java.util.Map;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.ajax.JSON.Convertor;
import org.eclipse.jetty.util.ajax.JSON.Output;

public class JSONPojoConvertorFactory implements JSON.Convertor
{
    private final JSON _json;
    private final boolean _fromJson;

    public JSONPojoConvertorFactory(JSON json)
    {
        if (json == null)
        {
            throw new IllegalArgumentException();
        }
        _json = json;
        _fromJson = true;
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
        {
            throw new IllegalArgumentException();
        }
        _json = json;
        _fromJson = fromJSON;
    }

    @Override
    public void toJSON(Object obj, Output out)
    {
        String clsName = obj.getClass().getName();
        Convertor convertor = _json.getConvertorFor(clsName);
        if (convertor == null)
        {
            try
            {
                Class cls = Loader.loadClass(clsName);
                convertor = new JSONPojoConvertor(cls, _fromJson);
                _json.addConvertorFor(clsName, convertor);
            }
            catch (ClassNotFoundException e)
            {
                JSON.LOG.warn(e);
            }
        }
        if (convertor != null)
        {
            convertor.toJSON(obj, out);
        }
    }

    @Override
    public Object fromJSON(Map object)
    {
        Map map = object;
        String clsName = (String)map.get("class");
        if (clsName != null)
        {
            Convertor convertor = _json.getConvertorFor(clsName);
            if (convertor == null)
            {
                try
                {
                    Class cls = Loader.loadClass(clsName);
                    convertor = new JSONPojoConvertor(cls, _fromJson);
                    _json.addConvertorFor(clsName, convertor);
                }
                catch (ClassNotFoundException e)
                {
                    JSON.LOG.warn(e);
                }
            }
            if (convertor != null)
            {
                return convertor.fromJSON(object);
            }
        }
        return map;
    }
}
