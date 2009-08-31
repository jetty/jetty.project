// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.util.ajax;

import java.util.Map;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.ajax.JSON.Convertor;
import org.eclipse.jetty.util.ajax.JSON.Output;

public class JSONPojoConvertorFactory implements JSON.Convertor
{
    private JSON _json=null;

    public JSONPojoConvertorFactory(JSON json)
    {
        if (json==null)
        {
            throw new IllegalArgumentException();
        }
        _json=json;
    }
    
    public void toJSON(Object obj, Output out)
    {
        String clsName=obj.getClass().getName();
        Convertor convertor=_json.getConvertorFor(clsName);
        if (convertor==null)
        {
            try
            {
                Class cls=Loader.loadClass(JSON.class,clsName);
                convertor=new JSONPojoConvertor(cls);
                _json.addConvertorFor(clsName, convertor);
             }
            catch (ClassNotFoundException e)
            {
                e.printStackTrace();
            }
        }
        if (convertor!=null)
        {
            convertor.toJSON(obj, out);
        }
    }

    public Object fromJSON(Map object)
    {
        Map map=object;
        String clsName=(String)map.get("class");
        if (clsName!=null)
        {
            Convertor convertor=_json.getConvertorFor(clsName);
            if (convertor==null)
            {
                try
                {
                    Class cls=Loader.loadClass(JSON.class,clsName);
                    convertor=new JSONPojoConvertor(cls);
                    _json.addConvertorFor(clsName, convertor);
                }
                catch (ClassNotFoundException e)
                {
                    e.printStackTrace();
                }
            }
            if (convertor!=null)
            {
                return convertor.fromJSON(object);
            }
        }
        return map;
    }
}
