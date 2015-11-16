//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ConcurrentHashSet<E> extends AbstractSet<E> implements Set<E>
{
    private final Map<E, Boolean> _map = new ConcurrentHashMap<E, Boolean>();
    private transient Set<E> _keys = _map.keySet();

    public ConcurrentHashSet()
    {
    }

    @Override
    public boolean add(E e)
    {
        return _map.put(e,Boolean.TRUE) == null;
    }

    @Override
    public void clear()
    {
        _map.clear();
    }

    @Override
    public boolean contains(Object o)
    {
        return _map.containsKey(o);
    }

    @Override
    public boolean containsAll(Collection<?> c)
    {
        return _keys.containsAll(c);
    }

    @Override
    public boolean equals(Object o)
    {
        return o == this || _keys.equals(o);
    }

    @Override
    public int hashCode()
    {
        return _keys.hashCode();
    }

    @Override
    public boolean isEmpty()
    {
        return _map.isEmpty();
    }

    @Override
    public Iterator<E> iterator()
    {
        return _keys.iterator();
    }

    @Override
    public boolean remove(Object o)
    {
        return _map.remove(o) != null;
    }

    @Override
    public boolean removeAll(Collection<?> c)
    {
        return _keys.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c)
    {
        return _keys.retainAll(c);
    }

    @Override
    public int size()
    {
        return _map.size();
    }

    @Override
    public Object[] toArray()
    {
        return _keys.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a)
    {
        return _keys.toArray(a);
    }

    @Override
    public String toString()
    {
        return _keys.toString();
    }
}
