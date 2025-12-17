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
 * A set of HostPort patterns.
 * <p>This is a {@link Set} of String patterns that are used to match
 * a {@link Predicate} over {@link HostPort} for containment semantics.
 * The patterns that may be set are defined in {@link HostPortPattern}.
 * </p>
 * <p>This class is designed to work with {@link IncludeExcludeSet}</p>
 *
 * @see IncludeExcludeSet
 * @see HostPortPattern
 */
public class HostPortSet extends AbstractSet<String> implements Set<String>, Predicate<HostPort>
{
    private final Map<String, HostPortPattern> _patterns = new HashMap<>();

    @Override
    public boolean add(String pattern)
    {
        return _patterns.put(pattern, HostPortPattern.from(pattern)) == null;
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
        for (HostPortPattern pattern : _patterns.values())
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
