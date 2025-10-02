package org.eclipse.jetty.util;

import java.nio.ByteBuffer;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A map using the instance (not contents) of a {@link ByteBuffer} as the key.
 * This class can be used to associate an object with a {@link ByteBuffer} passed through an
 * API that only takes {@link ByteBuffer}s.
 * @param <V>
 */
public class ByteBufferInstanceMap<V> implements Map<ByteBuffer, V>
{
    private final Map<byte[], Map.Entry<ByteBuffer, V>> _map = new IdentityHashMap<>();
    private final Set<Map.Entry<ByteBuffer, V>> _entrySet = new AbstractSet<>()
    {
        @Override
        public Iterator<Entry<ByteBuffer, V>> iterator()
        {
            return _map.values().iterator();
        }

        @Override
        public int size()
        {
            return _map.size();
        }
    };

    private static byte[] keyFor(Object key)
    {
        if (key instanceof ByteBuffer bb && bb.hasArray() && !bb.isReadOnly())
        {
            try
            {
                return bb.array();
            }
            catch (Throwable ignored)
            {
            }
        }
        return null;
    }

    private static byte[] keyFor(ByteBuffer key)
    {
        if (key == null || !key.hasArray() || key.isReadOnly())
            throw new IllegalArgumentException("Key must be a non-read-only heap ByteBuffer");
        try
        {
            return key.array();
        }
        catch (Throwable e)
        {
            throw new IllegalArgumentException("Key must be a non-read-only heap ByteBuffer", e);
        }
    }

    @Override
    public void clear()
    {
        _map.clear();
    }

    @Override
    public boolean containsKey(Object key)
    {
        byte[] array = keyFor(key);
        return array != null && _map.containsKey(array);
    }

    @Override
    public boolean containsValue(Object value)
    {
        return _map.values().stream().anyMatch(e -> Objects.equals(e.getValue(), value));
    }

    @Override
    public Set<Entry<ByteBuffer, V>> entrySet()
    {
        return _entrySet;
    }

    @Override
    public V get(Object key)
    {
        byte[] array = keyFor(key);
        if (array == null)
            return null;
        Map.Entry<ByteBuffer, V> entry = _map.get(array);
        return entry == null ? null : entry.getValue();
    }

    @Override
    public boolean isEmpty()
    {
        return _map.isEmpty();
    }

    @Override
    public Set<ByteBuffer> keySet()
    {
        return new AbstractSet<>()
        {
            @Override
            public Iterator<ByteBuffer> iterator()
            {
                return new Iterator<>()
                {
                    final Iterator<Map.Entry<ByteBuffer, V>> i = _map.values().iterator();

                    @Override
                    public boolean hasNext()
                    {
                        return i.hasNext();
                    }

                    @Override
                    public ByteBuffer next()
                    {
                        return i.next().getKey();
                    }

                    @Override
                    public void remove()
                    {
                        i.remove();
                    }
                };
            }

            @Override
            public int size()
            {
                return _map.size();
            }
        };
    }

    /**
     * creates a fresh empty heap buffer key and associates the value.
     */
    public ByteBuffer put(V value)
    {
        ByteBuffer key = ByteBuffer.wrap(new byte[0]);
        put(key, value);
        return key;
    }

    @Override
    public V put(ByteBuffer key, V value)
    {
        byte[] array = keyFor(key);
        BBEntry entry = new BBEntry(key, value);
        Map.Entry<ByteBuffer, V> old = _map.put(array, entry);
        return old == null ? null : old.getValue();
    }

    @Override
    public void putAll(Map<? extends ByteBuffer, ? extends V> m)
    {
        for (Map.Entry<? extends ByteBuffer, ? extends V> e : m.entrySet())
        {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public V remove(Object key)
    {
        byte[] array = keyFor(key);
        if (array == null)
            return null;
        Map.Entry<ByteBuffer, V> entry = _map.remove(array);
        return entry == null ? null : entry.getValue();
    }

    @Override
    public int size()
    {
        return _map.size();
    }

    @Override
    public Collection<V> values()
    {
        return new AbstractCollection<>()
        {
            @Override
            public Iterator<V> iterator()
            {
                return new Iterator<>()
                {
                    final Iterator<Map.Entry<ByteBuffer, V>> i = _map.values().iterator();

                    @Override
                    public boolean hasNext()
                    {
                        return i.hasNext();
                    }

                    @Override
                    public V next()
                    {
                        return i.next().getValue();
                    }

                    @Override
                    public void remove()
                    {
                        i.remove();
                    }
                };
            }

            @Override
            public int size()
            {
                return _map.size();
            }
        };
    }

    private final class BBEntry implements Map.Entry<ByteBuffer, V>
    {
        private final ByteBuffer _key;
        private V _value;

        BBEntry(ByteBuffer key, V value)
        {
            keyFor(key);
            _key = key;
            _value = value;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (!(obj instanceof ByteBufferInstanceMap<?>.BBEntry other))
                return false;
            return other.getKey() == getKey() && Objects.equals(other.getValue(), getValue());
        }

        @Override
        public ByteBuffer getKey()
        {
            return _key;
        }

        @Override
        public V getValue()
        {
            return _value;
        }

        @Override
        public int hashCode()
        {
            return _key.hashCode() ^ (_value == null ? 0 : _value.hashCode());
        }

        @Override
        public V setValue(V value)
        {
            V old = _value;
            _value = value;
            return old;
        }
    }
}
