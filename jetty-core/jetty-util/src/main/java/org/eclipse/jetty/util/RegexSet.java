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
