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

package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.TypeUtil;

class NamedEnvironment extends Attributes.Mapped implements Environment, Dumpable
{
    static final Map<String, Environment> ENVIRONMENTS = new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);
    static final ClassLoader DEFAULT_CLASSLOADER = NamedEnvironment.class.getClassLoader();

    private final String _name;
    private final ClassLoader _classLoader;

    NamedEnvironment(String name, ClassLoader classLoader)
    {
        _name = name;
        _classLoader = classLoader == null ? DEFAULT_CLASSLOADER : classLoader;
    }

    @Override
    public String getName()
    {
        return _name;
    }

    @Override
    public ClassLoader getClassLoader()
    {
        return _classLoader;
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent,
            this,
            new ClassLoaderDump(getClassLoader()),
            new DumpableCollection("Attributes " + _name, asAttributeMap().entrySet()));
    }

    @Override
    public int hashCode()
    {
        return _name.hashCode();
    }

    @Override
    public boolean equals(Object o)
    {
        // Only do instance equality
        return this == o;
    }

    @Override
    public String toString()
    {
        return "%s@%x{%s}".formatted(TypeUtil.toShortName(this.getClass()), hashCode(), _name);
    }
}
