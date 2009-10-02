// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.logging.impl;

import java.util.HashMap;
import java.util.Map;

public class CentralMDC
{
    private final static CentralMDC mdc = new CentralMDC();
    private ThreadLocalMap local;

    private CentralMDC()
    {
        local = new ThreadLocalMap();
    }

    public static void put(String key, String value)
    {
        if (mdc == null)
        {
            return;
        }
        mdc.internalPut(key,value);
    }

    private void internalPut(String key, String value)
    {
        if (local == null)
        {
            return;
        }

        HashMap<String, String> map = local.get();
        if (map == null)
        {
            map = new HashMap<String, String>();
            local.set(map);
        }
        map.put(key,value);
    }

    public static String get(String key)
    {
        if (mdc == null)
        {
            return null;
        }

        return mdc.internalGet(key);
    }

    private String internalGet(String key)
    {
        if (local == null)
        {
            return null;
        }

        if (key == null)
        {
            return null;
        }

        HashMap<String, String> map = local.get();
        if (map == null)
        {
            return null;
        }
        return map.get(key);
    }

    public static void remove(String key)
    {
        if (mdc == null)
        {
            return;
        }

        mdc.internalRemove(key);
    }

    private void internalRemove(String key)
    {
        if (local == null)
        {
            return;
        }

        if (key == null)
        {
            return;
        }

        HashMap<String, String> map = local.get();
        if (map == null)
        {
            return;
        }

        map.remove(key);
    }

    public static void clear()
    {
        if (mdc == null)
        {
            return;
        }

        mdc.internalClear();
    }

    private void internalClear()
    {
        if (local == null)
        {
            return;
        }

        HashMap<String, String> map = local.get();
        if (map == null)
        {
            return;
        }

        map.clear();
    }

    public static Map<String, String> getContextMap()
    {
        if (mdc == null)
        {
            return null;
        }

        return mdc.internalGetContextMap();
    }

    private Map<String, String> internalGetContextMap()
    {
        if (local == null)
        {
            return null;
        }

        HashMap<String, String> map = local.get();
        if (map == null)
        {
            return null;
        }

        Map<String, String> copy = new HashMap<String, String>();
        copy.putAll(map);

        return copy;
    }

    public static void setContextMap(Map<String, String> contextMap)
    {
        if (mdc == null)
        {
            return;
        }

        mdc.internalSetContextMap(contextMap);
    }

    private void internalSetContextMap(Map<String, String> contextMap)
    {
        if (local == null)
        {
            return;
        }

        HashMap<String, String> map = local.get();
        if (map == null)
        {
            map = new HashMap<String, String>();
            local.set(map);
        }
        else
        {
            map.clear();
        }
        map.putAll(contextMap);
    }
}
