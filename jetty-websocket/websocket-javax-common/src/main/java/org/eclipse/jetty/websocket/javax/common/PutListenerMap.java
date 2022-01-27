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

package org.eclipse.jetty.websocket.javax.common;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public class PutListenerMap implements Map<String, Object>
{
    private Map<String, Object> map;
    private BiConsumer<String, Object> listener;

    public PutListenerMap(Map<String, Object> map, BiConsumer<String, Object> listener)
    {
        this.map = map;
        this.listener = listener;

        // Notify listener for any existing entries in the Map.
        for (Map.Entry<String, Object> entry : map.entrySet())
        {
            listener.accept(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public int size()
    {
        return map.size();
    }

    @Override
    public boolean isEmpty()
    {
        return map.isEmpty();
    }

    @Override
    public boolean containsKey(Object key)
    {
        return map.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value)
    {
        return map.containsValue(value);
    }

    @Override
    public Object get(Object key)
    {
        return map.get(key);
    }

    @Override
    public Object put(String key, Object value)
    {
        listener.accept(key, value);
        return map.put(key, value);
    }

    @Override
    public Object remove(Object key)
    {
        return map.remove(key);
    }

    @Override
    public void putAll(Map<? extends String, ?> m)
    {
        for (Map.Entry<String, Object> entry : map.entrySet())
        {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void clear()
    {
        map.clear();
    }

    @Override
    public Set<String> keySet()
    {
        return map.keySet();
    }

    @Override
    public Collection<Object> values()
    {
        return map.values();
    }

    @Override
    public Set<Entry<String, Object>> entrySet()
    {
        return map.entrySet();
    }
}
