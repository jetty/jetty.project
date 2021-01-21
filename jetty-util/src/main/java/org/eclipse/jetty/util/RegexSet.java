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

package org.eclipse.jetty.util;

import java.util.AbstractSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A Set of Regular expressions strings.
 * <p>
 * Provides the efficient {@link #matches(String)} method to check for a match against all the combined Regex's
 */
public class RegexSet extends AbstractSet<String> implements Predicate<String>
{
    private final Set<String> _patterns = new HashSet<String>();
    private final Set<String> _unmodifiable = Collections.unmodifiableSet(_patterns);
    private Pattern _pattern;

    @Override
    public Iterator<String> iterator()
    {
        return _unmodifiable.iterator();
    }

    @Override
    public int size()
    {
        return _patterns.size();
    }

    @Override
    public boolean add(String pattern)
    {
        boolean added = _patterns.add(pattern);
        if (added)
            updatePattern();
        return added;
    }

    @Override
    public boolean remove(Object pattern)
    {
        boolean removed = _patterns.remove(pattern);

        if (removed)
            updatePattern();
        return removed;
    }

    @Override
    public boolean isEmpty()
    {
        return _patterns.isEmpty();
    }

    @Override
    public void clear()
    {
        _patterns.clear();
        _pattern = null;
    }

    private void updatePattern()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("^(");
        for (String pattern : _patterns)
        {
            if (builder.length() > 2)
                builder.append('|');
            builder.append('(');
            builder.append(pattern);
            builder.append(')');
        }
        builder.append(")$");
        _pattern = Pattern.compile(builder.toString());
    }

    @Override
    public boolean test(String s)
    {
        return _pattern != null && _pattern.matcher(s).matches();
    }

    public boolean matches(String s)
    {
        return _pattern != null && _pattern.matcher(s).matches();
    }
}
