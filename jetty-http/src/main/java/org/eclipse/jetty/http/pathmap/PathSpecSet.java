//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * A Set of PathSpec strings.
 * <p>
 * Used by {@link org.eclipse.jetty.util.IncludeExclude} logic
 */
public class PathSpecSet implements Set<String>, Predicate<String>
{
    private final Set<PathSpec> specs = new TreeSet<>();

    @Override
    public boolean test(String s)
    {
        for (PathSpec spec : specs)
        {
            if (spec.matches(s))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isEmpty()
    {
        return specs.isEmpty();
    }

    @Override
    public Iterator<String> iterator()
    {
        return new Iterator<String>()
        {
            private Iterator<PathSpec> iter = specs.iterator();

            @Override
            public boolean hasNext()
            {
                return iter.hasNext();
            }

            @Override
            public String next()
            {
                PathSpec spec = iter.next();
                if (spec == null)
                {
                    return null;
                }
                return spec.getDeclaration();
            }

            @Override
            public void remove()
            {
                throw new UnsupportedOperationException("Remove not supported by this Iterator");
            }
        };
    }

    @Override
    public int size()
    {
        return specs.size();
    }

    @Override
    public boolean contains(Object o)
    {
        if (o instanceof PathSpec)
        {
            return specs.contains(o);
        }
        if (o instanceof String)
        {
            return specs.contains(toPathSpec((String)o));
        }
        return false;
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
            return toPathSpec((String)o);
        }
        return toPathSpec(o.toString());
    }

    private PathSpec toPathSpec(String rawSpec)
    {
        if ((rawSpec == null) || (rawSpec.length() < 1))
        {
            throw new RuntimeException("Path Spec String must start with '^', '/', or '*.': got [" + rawSpec + "]");
        }
        if (rawSpec.charAt(0) == '^')
        {
            return new RegexPathSpec(rawSpec);
        }
        else
        {
            return new ServletPathSpec(rawSpec);
        }
    }

    @Override
    public Object[] toArray()
    {
        return toArray(new String[specs.size()]);
    }

    @Override
    public <T> T[] toArray(T[] a)
    {
        int i = 0;
        for (PathSpec spec : specs)
        {
            a[i++] = (T)spec.getDeclaration();
        }
        return a;
    }

    @Override
    public boolean add(String e)
    {
        return specs.add(toPathSpec(e));
    }

    @Override
    public boolean remove(Object o)
    {
        return specs.remove(asPathSpec(o));
    }

    @Override
    public boolean containsAll(Collection<?> coll)
    {
        for (Object o : coll)
        {
            if (!specs.contains(asPathSpec(o)))
                return false;
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends String> coll)
    {
        boolean ret = false;

        for (String s : coll)
        {
            ret |= add(s);
        }

        return ret;
    }

    @Override
    public boolean retainAll(Collection<?> coll)
    {
        List<PathSpec> collSpecs = new ArrayList<>();
        for (Object o : coll)
        {
            collSpecs.add(asPathSpec(o));
        }
        return specs.retainAll(collSpecs);
    }

    @Override
    public boolean removeAll(Collection<?> coll)
    {
        List<PathSpec> collSpecs = new ArrayList<>();
        for (Object o : coll)
        {
            collSpecs.add(asPathSpec(o));
        }
        return specs.removeAll(collSpecs);
    }

    @Override
    public void clear()
    {
        specs.clear();
    }
}
