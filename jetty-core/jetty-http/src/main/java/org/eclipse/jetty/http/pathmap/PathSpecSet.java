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

package org.eclipse.jetty.http.pathmap;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.function.Predicate;

/**
 * A Set of PathSpec strings.
 * <p>
 * Used by {@link org.eclipse.jetty.util.IncludeExclude} logic
 */
public class PathSpecSet extends AbstractSet<String> implements Predicate<String>
{
    private final PathMappings<Boolean> specs = new PathMappings<>();

    @Override
    public boolean test(String s)
    {
        return specs.getMatch(s) != null;
    }

    @Override
    public int size()
    {
        return specs.size();
    }

    private PathSpec asPathSpec(Object o)
    {
        if (o == null)
        {
            return null;
        }
        if (o instanceof PathSpec)
        {
            return (PathSpec)o;
        }
        if (o instanceof String)
        {
            return PathMappings.asPathSpec((String)o);
        }
        return PathMappings.asPathSpec(o.toString());
    }

    @Override
    public boolean add(String s)
    {
        return specs.put(PathMappings.asPathSpec(s), Boolean.TRUE);
    }

    @Override
    public boolean remove(Object o)
    {
        return specs.remove(asPathSpec(o));
    }

    @Override
    public void clear()
    {
        specs.reset();
    }

    @Override
    public Iterator<String> iterator()
    {
        final Iterator<MappedResource<Boolean>> iterator = specs.iterator();
        return new Iterator<String>()
        {
            @Override
            public boolean hasNext()
            {
                return iterator.hasNext();
            }

            @Override
            public String next()
            {
                return iterator.next().getPathSpec().getDeclaration();
            }
        };
    }
}
