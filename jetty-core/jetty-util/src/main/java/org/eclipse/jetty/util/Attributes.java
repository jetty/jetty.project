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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.component.Dumpable;

/**
 * Attributes.
 * Interface commonly used for storing attributes.
 */
public interface Attributes
{
    /**
     * Remove an attribute
     * @param name the attribute to remove
     * @return the value of the attribute if removed, else null
     */
    Object removeAttribute(String name);

    /**
     * Set an attribute
     * @param name the attribute to set
     * @param attribute the value to set. A null value is equivalent to removing the attribute.
     * @return the previous value of the attribute if set, else null
     */
    Object setAttribute(String name, Object attribute);

    /**
     * Get an attribute
     * @param name the attribute to get
     * @return the value of the attribute
     */
    Object getAttribute(String name);

    /**
     * Get the immutable set of attribute names.
     * @return Set of attribute names
     */
    // TODO: change to getAttributeNames() once jetty-core is cleaned of servlet-api usages
    Set<String> getAttributeNameSet();

    // TODO something better than this
    default Map<String, Object> asAttributeMap()
    {
        return new AbstractMap<>()
        {
            @Override
            public Set<Entry<String, Object>> entrySet()
            {
                return new AbstractSet<>()
                {
                    Iterator<String> names = getAttributeNameSet().iterator();

                    @Override
                    public Iterator<Entry<String, Object>> iterator()
                    {
                        return new Iterator<>()
                        {
                            @Override
                            public boolean hasNext()
                            {
                                return names.hasNext();
                            }

                            @Override
                            public Entry<String, Object> next()
                            {
                                String name = names.next();
                                return new Map.Entry<>()
                                {
                                    @Override
                                    public String getKey()
                                    {
                                        return name;
                                    }

                                    @Override
                                    public Object getValue()
                                    {
                                        return getAttribute(name);
                                    }

                                    @Override
                                    public Object setValue(Object value)
                                    {
                                        throw new UnsupportedOperationException();
                                    }
                                };
                            }
                        };
                    }

                    @Override
                    public int size()
                    {
                        return 0;
                    }
                };
            }
        };
    }


    /**
     * Clear all attribute names
     */
    void clearAttributes();

    /** Unwrap all  {@link Wrapper}s of the attributes
     * @param attributes The attributes to unwrap, which may be a  {@link Wrapper}.
     * @return The core attributes
     */
    static Attributes unwrap(Attributes attributes)
    {
        while (attributes instanceof Wrapper)
        {
            attributes = ((Wrapper)attributes).getWrapped();
        }
        return attributes;
    }

    /** Unwrap attributes to a specific attribute  {@link Wrapper}.
     * @param attributes The attributes to unwrap, which may be a {@link Wrapper}
     * @param target The target  {@link Wrapper} class.
     * @param <T> The type of the target  {@link Wrapper}.
     * @return The outermost {@link Wrapper} of the matching type of null if not found.
     */
    @SuppressWarnings("unchecked")
    static <T extends Attributes.Wrapper> T unwrap(Attributes attributes, Class<T> target)
    {
        while (true)
        {
            if (target.isAssignableFrom(attributes.getClass()))
                return (T)attributes;

            if (attributes instanceof Wrapper wrapper)
                attributes = wrapper.getWrapped();
            else
                return null;
        }
    }

    /**
     * A Wrapper of attributes
     */
    class Wrapper implements Attributes
    {
        private final Attributes _wrapped;

        public Wrapper(Attributes wrapped)
        {
            _wrapped = wrapped;
        }

        public Attributes getWrapped()
        {
            return _wrapped;
        }

        @Override
        public Object removeAttribute(String name)
        {
            return _wrapped.removeAttribute(name);
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            return _wrapped.setAttribute(name, attribute);
        }

        @Override
        public Object getAttribute(String name)
        {
            return _wrapped.getAttribute(name);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            return _wrapped.getAttributeNameSet();
        }

        @Override
        public void clearAttributes()
        {
            _wrapped.clearAttributes();
        }

        // TODO: remove? or fix (don't want the wrapped and wrapper to match)
        @Override
        public int hashCode()
        {
            return _wrapped.hashCode();
        }

        // TODO: remove? or fix (don't want the wrapped and wrapper to match)
        @Override
        public boolean equals(Object obj)
        {
            return _wrapped.equals(obj);
        }
    }

    /**
     * An Attributes implementation backed by a {@link ConcurrentHashMap}.
     */
    class Mapped implements Attributes
    {
        private final java.util.concurrent.ConcurrentMap<String, Object> _map = new ConcurrentHashMap<>();
        private final Set<String> _names = Collections.unmodifiableSet(_map.keySet());

        public Mapped()
        {
        }

        public Mapped(Mapped attributes)
        {
            _map.putAll(attributes._map);
        }

        @Override
        public Map<String, Object> asAttributeMap()
        {
            return _map;
        }

        @Override
        public Object removeAttribute(String name)
        {
            return _map.remove(name);
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            if (attribute == null)
                return _map.remove(name);

            return _map.put(name, attribute);
        }

        @Override
        public Object getAttribute(String name)
        {
            return _map.get(name);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            return _names;
        }

        @Override
        public void clearAttributes()
        {
            _map.clear();
        }

        public int size()
        {
            return _map.size();
        }

        @Override
        public String toString()
        {
            return _map.toString();
        }

        public void addAll(Attributes attributes)
        {
            for (String name : attributes.getAttributeNameSet())
                setAttribute(name, attributes.getAttribute(name));
        }

        public Set<Map.Entry<String, Object>> getAttributeEntrySet()
        {
            return _map.entrySet();
        }

        // TODO: remove? or fix (don't want the wrapped and wrapper to match)
        @Override
        public int hashCode()
        {
            return _map.hashCode();
        }

        // TODO: remove? or fix (don't want the wrapped and wrapper to match)
        @Override
        public boolean equals(Object o)
        {
            if (o instanceof Attributes)
            {
                Attributes a = (Attributes)o;
                for (Map.Entry<String, Object> e : _map.entrySet())
                {
                    if (!Objects.equals(e.getValue(), a.getAttribute(e.getKey())))
                        return false;
                }
                return true;
            }
            return false;
        }
    }

    /**
     * An {@link Attributes} implementation that lazily creates a backing map iff it is actually needed.
     */
    class Lazy implements Attributes, Dumpable
    {
        private final AtomicReference<ConcurrentMap<String, Object>> _map = new AtomicReference<>();

        public Lazy()
        {
        }

        private ConcurrentMap<String, Object> map()
        {
            return _map.get();
        }

        private ConcurrentMap<String, Object> ensureMap()
        {
            while (true)
            {
                ConcurrentMap<String, Object> map = map();
                if (map != null)
                    return map;
                map = new ConcurrentHashMap<>();
                if (_map.compareAndSet(null, map))
                    return map;
            }
        }

        @Override
        public Map<String, Object> asAttributeMap()
        {
            return ensureMap();
        }

        @Override
        public Object removeAttribute(String name)
        {
            Map<String, Object> map = map();
            return map == null ? null : map.remove(name);
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            if (attribute == null)
                return removeAttribute(name);
            return ensureMap().put(name, attribute);
        }

        @Override
        public Object getAttribute(String name)
        {
            Map<String, Object> map = map();
            return map == null ? null : map.get(name);
        }

        public Collection<Object> getAttributeEntriesSet()
        {
            return map().values();
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            return Collections.unmodifiableSet(keySet());
        }

        @Override
        public void clearAttributes()
        {
            Map<String, Object> map = map();
            if (map != null)
                map.clear();
        }

        public int size()
        {
            Map<String, Object> map = map();
            return map == null ? 0 : map.size();
        }

        @Override
        public String toString()
        {
            Map<String, Object> map = map();
            return map == null ? "{}" : map.toString();
        }

        private Set<String> keySet()
        {
            Map<String, Object> map = map();
            return map == null ? Collections.emptySet() : map.keySet();
        }

        public void addAll(Attributes attributes)
        {
            for (String name : attributes.getAttributeNameSet())
                setAttribute(name, attributes.getAttribute(name));
        }

        @Override
        public String dump()
        {
            return Dumpable.dump(this);
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            Dumpable.dumpObjects(out, indent, String.format("%s@%x", this.getClass().getSimpleName(), hashCode()), map());
        }
    }

    /**
     * An {@link Attributes} implementation backed by another {@link Attributes} instance, which is treated as immutable, but with a
     * ConcurrentHashMap used as a mutable layer over it.
     * // TODO: can we extend Wrapper with a contains wrapped?
     */
    class Layer implements Attributes
    {
        private static final Object REMOVED = new Object()
        {
            @Override
            public String toString()
            {
                return "REMOVED";
            }
        };

        private final Attributes _persistent;
        private final Attributes _layer;

        public Layer(Attributes persistent)
        {
            this(persistent, new Attributes.Mapped());
        }

        public Layer(Attributes persistent, Attributes layer)
        {
            _persistent = persistent;
            _layer = layer;
        }

        @Override
        public Object removeAttribute(String name)
        {
            Object p = _persistent.getAttribute(name);
            try
            {
                Object v = _layer.setAttribute(name, REMOVED);
                if (v == REMOVED)
                    return null;
                if (v != null)
                    return v;
                return p;
            }
            finally
            {
                if (p == null)
                    _layer.removeAttribute(name);
            }
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            if (attribute == null)
                return removeAttribute(name);
            Object v = _layer.setAttribute(name, attribute);
            return v == REMOVED ? null : v;
        }

        @Override
        public Object getAttribute(String name)
        {
            Object v = _layer.getAttribute(name);
            if (v != null)
                return v == REMOVED ? null : v;
            return _persistent.getAttribute(name);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            Set<String> names = new HashSet<>(_persistent.getAttributeNameSet());

            for (String name : _layer.getAttributeNameSet())
            {
                if (_layer.getAttribute(name) == REMOVED)
                    names.remove(name);
                else
                    names.add(name);
            }
            return Collections.unmodifiableSet(names);
        }

        @Override
        public void clearAttributes()
        {
            _layer.clearAttributes();
            for (String name : _persistent.getAttributeNameSet())
                _layer.setAttribute(name, REMOVED);
        }

        // TODO: remove? or fix (don't want the wrapped and wrapper to match)
        @Override
        public int hashCode()
        {
            int hash = 0;
            for (String name : getAttributeNameSet())
                hash += name.hashCode() ^ getAttribute(name).hashCode();
            return hash;
        }

        // TODO: remove? or fix (don't want the wrapped and wrapper to match)
        @Override
        public boolean equals(Object o)
        {
            if (o instanceof Attributes)
            {
                Attributes a = (Attributes)o;
                Set<String> ours = getAttributeNameSet();
                Set<String> theirs = getAttributeNameSet();
                if (!ours.equals(theirs))
                    return false;

                for (String s : ours)
                {
                    if (!Objects.equals(getAttribute(s), a.getAttribute(s)))
                        return false;
                }
                return true;
            }
            return false;
        }
    }
}
