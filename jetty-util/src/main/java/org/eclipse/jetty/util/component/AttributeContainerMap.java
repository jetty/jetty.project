//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
    public Set<String> getAttributeNameSet()
    {
        return _map.keySet();
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
