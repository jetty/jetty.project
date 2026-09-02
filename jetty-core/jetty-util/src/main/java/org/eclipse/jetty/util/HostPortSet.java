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

import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A set of host:port patterns.
 * <p>This {@link Set} stores pattern strings and acts as a {@link Predicate}
 * over {@link HostPort}.</p>
 * <p>The supported patterns are defined by {@link HostPortPredicate}.</p>
 * <p>This class is designed to work with {@link IncludeExcludeSet}</p>
 *
 * @see IncludeExcludeSet
 * @see HostPortPredicate
 */
public class HostPortSet extends AbstractSet<String> implements Set<String>, Predicate<HostPort>
{
    private final Map<String, HostPortPredicate> _patterns = new HashMap<>();

    @Override
    public boolean add(String pattern)
    {
        return _patterns.put(pattern, HostPortPredicate.from(pattern)) == null;
    }

    @Override
    public boolean remove(Object pattern)
    {
        return _patterns.remove(pattern) != null;
    }

    @Override
    public Iterator<String> iterator()
    {
        return _patterns.keySet().iterator();
    }

    @Override
    public int size()
    {
        return _patterns.size();
    }

    @Override
    public boolean test(HostPort hostPort)
    {
        if (hostPort == null)
            return false;
        for (HostPortPredicate pattern : _patterns.values())
        {
            if (pattern.test(hostPort))
                return true;
        }
        return false;
    }

    @Override
    public void clear()
    {
        _patterns.clear();
    }

    @Override
    public boolean contains(Object o)
    {
        return _patterns.containsKey(o);
    }
}
