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

package org.eclipse.jetty.ee9.webapp;

import java.net.URI;
import java.util.Map;

import org.eclipse.jetty.util.IncludeExcludeSet;

public class ClassMatcher extends org.eclipse.jetty.util.ClassMatcher
{
    public ClassMatcher()
    {
        super();
    }

    public ClassMatcher(ClassMatcher patterns)
    {
        super(patterns);
    }

    public ClassMatcher(org.eclipse.jetty.util.ClassMatcher patterns)
    {
        super(patterns);
    }

    public ClassMatcher(String... patterns)
    {
        super(patterns);
    }

    public ClassMatcher(String pattern)
    {
        super(pattern);
    }

    protected ClassMatcher(Map<String, Entry> entries, IncludeExcludeSet<Entry, String> patterns, IncludeExcludeSet<Entry, URI> locations)
    {
        super(entries, patterns, locations);
    }

    @Override
    public ClassMatcher asImmutable()
    {
        return new ClassMatcher(Map.copyOf(_entries),
            _patterns.asImmutable(),
            _locations.asImmutable());
    }
}
