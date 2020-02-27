//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.net.InetAddress;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A set of InetAddress patterns.
 * <p>This is a {@link Set} of String patterns that are used to match
 * a {@link Predicate} over InetAddress for containment semantics.
 * The patterns that may be set are defined in {@link InetAddressPattern}.
 * </p>
 * <p>This class is designed to work with {@link IncludeExcludeSet}</p>
 *
 * @see IncludeExcludeSet
 */
public class InetAddressSet extends AbstractSet<String> implements Set<String>, Predicate<InetAddress>
{
    private Map<String, InetAddressPattern> _patterns = new HashMap<>();

    @Override
    public boolean add(String pattern)
    {
        return _patterns.put(pattern, InetAddressPattern.from(pattern)) == null;
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
    public boolean test(InetAddress address)
    {
        if (address == null)
            return false;
        for (InetAddressPattern pattern : _patterns.values())
        {
            if (pattern.test(address))
                return true;
        }
        return false;
    }
}
