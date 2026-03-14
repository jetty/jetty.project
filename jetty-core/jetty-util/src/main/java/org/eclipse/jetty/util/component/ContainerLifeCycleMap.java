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

package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.util.TypeUtil;

/**
 * A {@link Map} implementation that manages its values as beans in a {@link ContainerLifeCycle}.
 * <p>
 * When values are added to the map via {@link #put(Object, Object)} or {@link #putAll(Map)},
 * they are also added as managed beans. When values are removed via {@link #remove(Object)},
 * {@link #clear()}, or through collection views, they are removed as beans.
 * </p>
 * <p>
 * The lifecycle of the values is tied to this container: when the container starts,
 * all managed values are started; when it stops, they are stopped.
 * </p>
 * <p>
 * Like {@link ContainerLifeCycle}, this class is not thread-safe for concurrent mutation or access.
 * Applications must provide external synchronization if instances are shared between threads.
 * </p>
 *
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values, must extend {@link LifeCycle}
 */
public class ContainerLifeCycleMap<K, V extends LifeCycle> extends ContainerLifeCycle implements Map<K, V>
{
    private final Map<K, V> _map;

    /**
     * Creates a new ContainerLifeCycleMap backed by a HashMap.
     */
    public ContainerLifeCycleMap()
    {
        _map = new HashMap<>();
    }

    /**
     * Creates a new ContainerLifeCycleMap backed by the provided Map.
     * <p>
     * This allows using custom Map implementations, such as a TreeMap
     * with case-insensitive key ordering.
     * </p>
     *
     * @param map the backing Map implementation to use
     */
    public ContainerLifeCycleMap(Map<K, V> map)
    {
        _map = map;
    }

    @Override
    public int size()
    {
        return _map.size();
    }

    @Override
    public boolean isEmpty()
    {
        return _map.isEmpty();
    }

    @Override
    public boolean containsKey(Object key)
    {
        return _map.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value)
    {
        return _map.containsValue(value);
    }

    @Override
    public V get(Object key)
    {
        return _map.get(key);
    }

    @Override
    public V put(K key, V value)
    {
        V old = _map.put(key, value);
        updateBean(old, value);
        return old;
    }

    @Override
    public V remove(Object key)
    {
        V removed = _map.remove(key);
        if (removed != null)
            removeBean(removed);
        return removed;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m)
    {
        for (Entry<? extends K, ? extends V> entry : m.entrySet())
        {
            V old = _map.put(entry.getKey(), entry.getValue());
            updateBean(old, entry.getValue());
        }
    }

    @Override
    public void clear()
    {
        _map.clear();
        removeBeans();
    }

    @Override
    public Set<K> keySet()
    {
        return new KeySet();
    }

    @Override
    public Collection<V> values()
    {
        return new Values();
    }

    @Override
    public Set<Entry<K, V>> entrySet()
    {
        return new EntrySet();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObject(out, this);
        Dumpable.dumpMapEntries(out, indent, _map, true);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{size=%d}", TypeUtil.toShortName(this.getClass()), hashCode(), _map.size());
    }

    /**
     * A wrapped key set that intercepts removal operations to properly remove beans.
     */
    private class KeySet extends AbstractSet<K>
    {
        @Override
        public Iterator<K> iterator()
        {
            return new KeyIterator();
        }

        @Override
        public int size()
        {
            return ContainerLifeCycleMap.this.size();
        }

        @Override
        public boolean contains(Object o)
        {
            return ContainerLifeCycleMap.this.containsKey(o);
        }

        @Override
        public boolean remove(Object o)
        {
            return ContainerLifeCycleMap.this.remove(o) != null;
        }

        @Override
        public void clear()
        {
            ContainerLifeCycleMap.this.clear();
        }
    }

    /**
     * A wrapped values collection that intercepts removal operations to properly remove beans.
     */
    private class Values extends AbstractCollection<V>
    {
        @Override
        public Iterator<V> iterator()
        {
            return new ValueIterator();
        }

        @Override
        public int size()
        {
            return ContainerLifeCycleMap.this.size();
        }

        @Override
        public boolean contains(Object o)
        {
            return ContainerLifeCycleMap.this.containsValue(o);
        }

        @Override
        public void clear()
        {
            ContainerLifeCycleMap.this.clear();
        }
    }

    /**
     * A wrapped entry set that intercepts removal and setValue operations to properly manage beans.
     */
    private class EntrySet extends AbstractSet<Entry<K, V>>
    {
        @Override
        public Iterator<Entry<K, V>> iterator()
        {
            return new EntryIterator();
        }

        @Override
        public int size()
        {
            return ContainerLifeCycleMap.this.size();
        }

        @Override
        public boolean contains(Object o)
        {
            if (!(o instanceof Entry<?, ?> e))
                return false;
            V value = _map.get(e.getKey());
            return value != null && value.equals(e.getValue());
        }

        @Override
        public boolean remove(Object o)
        {
            if (!(o instanceof Entry<?, ?> e))
                return false;
            V value = _map.get(e.getKey());
            if (value != null && value.equals(e.getValue()))
            {
                _map.remove(e.getKey());
                removeBean(value);
                return true;
            }
            return false;
        }

        @Override
        public void clear()
        {
            ContainerLifeCycleMap.this.clear();
        }
    }

    /**
     * Iterator over keys that properly removes beans when iterator.remove() is called.
     */
    private class KeyIterator implements Iterator<K>
    {
        private final Iterator<Entry<K, V>> _iterator;
        private Entry<K, V> _current;

        KeyIterator()
        {
            // Create a copy to avoid ConcurrentModificationException
            _iterator = new HashMap<>(_map).entrySet().iterator();
        }

        @Override
        public boolean hasNext()
        {
            return _iterator.hasNext();
        }

        @Override
        public K next()
        {
            _current = _iterator.next();
            return _current.getKey();
        }

        @Override
        public void remove()
        {
            if (_current == null)
                throw new IllegalStateException();
            ContainerLifeCycleMap.this.remove(_current.getKey());
            _current = null;
        }
    }

    /**
     * Iterator over values that properly removes beans when iterator.remove() is called.
     */
    private class ValueIterator implements Iterator<V>
    {
        private final Iterator<Entry<K, V>> _iterator;
        private Entry<K, V> _current;

        ValueIterator()
        {
            // Create a copy to avoid ConcurrentModificationException
            _iterator = new HashMap<>(_map).entrySet().iterator();
        }

        @Override
        public boolean hasNext()
        {
            return _iterator.hasNext();
        }

        @Override
        public V next()
        {
            _current = _iterator.next();
            return _current.getValue();
        }

        @Override
        public void remove()
        {
            if (_current == null)
                throw new IllegalStateException();
            ContainerLifeCycleMap.this.remove(_current.getKey());
            _current = null;
        }
    }

    /**
     * Iterator over entries that properly manages beans when iterator.remove() or entry.setValue() is called.
     */
    private class EntryIterator implements Iterator<Entry<K, V>>
    {
        private final Iterator<Entry<K, V>> _iterator;
        private Entry<K, V> _current;

        EntryIterator()
        {
            // Create a copy to avoid ConcurrentModificationException
            _iterator = new HashMap<>(_map).entrySet().iterator();
        }

        @Override
        public boolean hasNext()
        {
            return _iterator.hasNext();
        }

        @Override
        public Entry<K, V> next()
        {
            _current = _iterator.next();
            // Return a wrapped entry that intercepts setValue
            return new WrappedEntry(_current.getKey());
        }

        @Override
        public void remove()
        {
            if (_current == null)
                throw new IllegalStateException();
            ContainerLifeCycleMap.this.remove(_current.getKey());
            _current = null;
        }
    }

    /**
     * A wrapped entry that intercepts setValue to properly manage beans.
     */
    private class WrappedEntry implements Entry<K, V>
    {
        private final K _key;

        WrappedEntry(K key)
        {
            _key = key;
        }

        @Override
        public K getKey()
        {
            return _key;
        }

        @Override
        public V getValue()
        {
            return ContainerLifeCycleMap.this.get(_key);
        }

        @Override
        public V setValue(V value)
        {
            return ContainerLifeCycleMap.this.put(_key, value);
        }

        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof Entry<?, ?> e))
                return false;
            return _key.equals(e.getKey()) && getValue().equals(e.getValue());
        }

        @Override
        public int hashCode()
        {
            V value = getValue();
            return _key.hashCode() ^ (value == null ? 0 : value.hashCode());
        }
    }
}
