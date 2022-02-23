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

package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.thread.AutoLock;

/**
 * An Attributes implementation that holds it's values in an immutable {@link ContainerLifeCycle}
 */
public class AttributeContainerMap extends ContainerLifeCycle implements Attributes
{
    private final AutoLock _lock = new AutoLock();
    private final Map<String, Object> _map = new HashMap<>();

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        try (AutoLock l = _lock.lock())
        {
            Object old = _map.put(name, attribute);
            updateBean(old, attribute);
            return old;
        }
    }

    @Override
    public Object removeAttribute(String name)
    {
        try (AutoLock l = _lock.lock())
        {
            Object removed = _map.remove(name);
            if (removed != null)
                removeBean(removed);
            return removed;
        }
    }

    @Override
    public Object getAttribute(String name)
    {
        try (AutoLock l = _lock.lock())
        {
            return _map.get(name);
        }
    }

    @Override
    public Set<String> getAttributeNames()
    {
        try (AutoLock l = _lock.lock())
        {
            return _map.keySet();
        }
    }

    @Override
    public void clearAttributes()
    {
        try (AutoLock l = _lock.lock())
        {
            _map.clear();
            this.removeBeans();
        }
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
        return String.format("%s@%x{size=%d}", this.getClass().getSimpleName(), hashCode(), _map.size());
    }
}
