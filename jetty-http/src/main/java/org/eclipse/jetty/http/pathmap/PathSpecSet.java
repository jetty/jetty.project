//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http.pathmap;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Objects;
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
        return specs.getMatched(s) != null;
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

        return PathSpec.from(Objects.toString(o));
    }

    @Override
    public boolean add(String s)
    {
        return specs.put(PathSpec.from(s), Boolean.TRUE);
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
