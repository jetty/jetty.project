//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Collections;
import java.util.HashSet;
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
    Set<String> getAttributeNames();

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
        while (attributes instanceof Wrapper)
        {
            if (target.isAssignableFrom(attributes.getClass()))
                return (T)attributes;
            attributes = ((Wrapper)attributes).getWrapped();
        }
        return null;
    }

    /**
     * A Wrapper of attributes
     */
    class Wrapper implements Attributes
    {
        protected final Attributes _attributes;

        public Wrapper(Attributes attributes)
        {
            _attributes = attributes;
        }

        public Attributes getWrapped()
        {
            return _attributes;
        }

        @Override
        public Object removeAttribute(String name)
        {
            return _attributes.removeAttribute(name);
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            return _attributes.setAttribute(name, attribute);
        }

        @Override
        public Object getAttribute(String name)
        {
            return _attributes.getAttribute(name);
        }

        @Override
        public Set<String> getAttributeNames()
        {
            return _attributes.getAttributeNames();
        }

        @Override
        public void clearAttributes()
        {
            _attributes.clearAttributes();
        }

        @Override
        public int hashCode()
        {
            return _attributes.hashCode();
        }

        @Override
        public boolean equals(Object obj)
        {
            return _attributes.equals(obj);
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
        public Set<String> getAttributeNames()
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
            for (String name : attributes.getAttributeNames())
                setAttribute(name, attributes.getAttribute(name));
        }

        @Override
        public int hashCode()
        {
            return _map.hashCode();
        }

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

        @Override
        public Set<String> getAttributeNames()
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
            for (String name : attributes.getAttributeNames())
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
        private final java.util.concurrent.ConcurrentMap<String, Object> _map = new ConcurrentHashMap<>();

        public Layer(Attributes persistent)
        {
            _persistent = persistent;
        }

        @Override
        public Object removeAttribute(String name)
        {
            Object p = _persistent.getAttribute(name);
            try
            {
                Object v = _map.put(name, REMOVED);
                if (v == REMOVED)
                    return null;
                if (v != null)
                    return v;
                return p;
            }
            finally
            {
                if (p == null)
                    _map.remove(name);
            }
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            if (attribute == null)
                return removeAttribute(name);
            Object v = _map.put(name, attribute);
            return v == REMOVED ? null : v;
        }

        @Override
        public Object getAttribute(String name)
        {
            Object v = _map.get(name);
            if (v != null)
                return v == REMOVED ? null : v;
            return _persistent.getAttribute(name);
        }

        @Override
        public Set<String> getAttributeNames()
        {
            Set<String> names = new HashSet<>(_persistent.getAttributeNames());
            for (Map.Entry<String, Object> entry : _map.entrySet())
            {
                if (entry.getValue() == REMOVED)
                    names.remove(entry.getKey());
                else
                    names.add(entry.getKey());
            }
            return Collections.unmodifiableSet(names);
        }

        @Override
        public void clearAttributes()
        {
            for (String n : _persistent.getAttributeNames())
                _map.put(n, REMOVED);
            _map.entrySet().removeIf(e -> e.getValue() != REMOVED);
        }

        @Override
        public int hashCode()
        {
            int hash = 0;
            for (String name : getAttributeNames())
                hash += name.hashCode() ^ getAttribute(name).hashCode();
            return hash;
        }

        @Override
        public boolean equals(Object o)
        {
            if (o instanceof Attributes)
            {
                Attributes a = (Attributes)o;
                Set<String> ours = getAttributeNames();
                Set<String> theirs = getAttributeNames();
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
