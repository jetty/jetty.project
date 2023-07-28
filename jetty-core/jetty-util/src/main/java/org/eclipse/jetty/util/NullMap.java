//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * A Map Implementation that never has entries.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public class NullMap<K, V> implements Map<K, V>
{
    @Override
    public int size()
    {
        // always empty
        return 0;
    }

    @Override
    public boolean isEmpty()
    {
        // always empty
        return true;
    }

    @Override
    public boolean containsKey(Object key)
    {
        // always false
        return false;
    }

    @Override
    public boolean containsValue(Object value)
    {
        // always false
        return false;
    }

    @Override
    public V get(Object key)
    {
        // always null
        return null;
    }

    @Override
    public V put(K key, V value)
    {
        // no-op
        return null;
    }

    @Override
    public V remove(Object key)
    {
        return null;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m)
    {
        // no-op
    }

    @Override
    public void clear()
    {
        // no-op
    }

    @Override
    public Set<K> keySet()
    {
        return Collections.emptySet();
    }

    @Override
    public Collection<V> values()
    {
        return Collections.emptySet();
    }

    @Override
    public Set<Entry<K, V>> entrySet()
    {
        return Collections.emptySet();
    }
}
