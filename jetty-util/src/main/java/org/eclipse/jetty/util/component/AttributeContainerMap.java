//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.util.Attributes;

/**
 * An Attributes implementation that holds it's values in an immutable {@link ContainerLifeCycle}
 */
public class AttributeContainerMap extends ContainerLifeCycle implements Attributes
{
    private final Map<String, Object> _map = new HashMap<>();

    @Override
    public synchronized void setAttribute(String name, Object attribute)
    {
        Object old = _map.put(name, attribute);
        updateBean(old, attribute);
    }

    @Override
    public synchronized void removeAttribute(String name)
    {
        Object removed = _map.remove(name);
        if (removed != null)
            removeBean(removed);
    }

    @Override
    public synchronized Object getAttribute(String name)
    {
        return _map.get(name);
    }

    @Override
    public synchronized Enumeration<String> getAttributeNames()
    {
        return Collections.enumeration(_map.keySet());
    }

    @Override
    public synchronized void clearAttributes()
    {
        _map.clear();
        this.removeBeans();
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
