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

import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
     * @param attribute the value to set
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
            attributes = ((Wrapper)attributes).getAttributes();
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
            attributes = ((Wrapper)attributes).getAttributes();
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

        public Attributes getAttributes()
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

        public Set<Map.Entry<String, Object>> getAttributes()
        {
            return _map.entrySet();
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
     * An Attributes implementation backed by another Attributes instance, which is treated as immutable, but with a
     * ConcurrentHashMap used as a mutable layer over it.
     */
    class Layer implements Attributes
    {
        private static final Object REMOVED = "REMOVED";
        private final Attributes _persistent;
        private final java.util.concurrent.ConcurrentMap<String, Object> _map = new ConcurrentHashMap<>();
        private final Set<String> _names;

        public Layer(Attributes persistent)
        {
            _persistent = persistent;
            _names = new LayeredNamesSet();
        }

        @Override
        public Object removeAttribute(String name)
        {
            if (_persistent.getAttributeNames().contains(name))
            {
                Object v = _map.put(name, REMOVED);
                if (v == REMOVED)
                    return null;
                if (v != null)
                    return v;
                return _persistent.getAttribute(name);
            }
            Object v = _map.remove(name);
            return v == REMOVED ? null : v;
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
            return _names;
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
            int hash = super.hashCode();
            for (String name : getAttributeNames())
                hash = hash ^ name.hashCode() ^ getAttribute(name).hashCode();
            return hash;
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

        private class LayeredNamesSet extends AbstractSet<String>
        {
            @Override
            public void clear()
            {
                clearAttributes();
            }

            @Override
            public Iterator<String> iterator()
            {
                Iterator<Map.Entry<String, Object>> m = _map.entrySet().iterator();
                Iterator<String> p = _persistent.getAttributeNames().iterator();
                return new Iterator<>()
                {
                    Object _next;

                    @Override
                    public boolean hasNext()
                    {
                        if (_next == null)
                        {
                            while (m.hasNext())
                            {
                                Map.Entry<String, Object> next = m.next();
                                if (next.getValue() != REMOVED)
                                {
                                    _next = next.getKey();
                                    break;
                                }
                            }
                        }

                        if (_next == null)
                        {
                            while (p.hasNext())
                            {
                                Object next = p.next();
                                if (_map.containsKey(next))
                                    continue;
                                _next = next;
                                break;
                            }
                        }

                        return _next != null;
                    }

                    @Override
                    public String next()
                    {
                        if (hasNext())
                        {
                            String next = String.valueOf(_next);
                            _next = null;
                            return next;
                        }
                        throw new NoSuchElementException();
                    }
                };
            }

            @Override
            public int size()
            {
                int s = 0;
                for (Iterator<String> i = iterator(); i.hasNext(); i.next())
                    s++;
                return s;
            }
        }
    }
}
