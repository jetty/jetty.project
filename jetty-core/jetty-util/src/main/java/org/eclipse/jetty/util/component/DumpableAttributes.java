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

import org.eclipse.jetty.util.Attributes;

public class DumpableAttributes implements Dumpable
{
    private final String _name;
    private final Attributes _attributes;

    public DumpableAttributes(String name, Attributes attributes)
    {
        _name = name;
        _attributes = attributes;
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Object[] array = _attributes.asAttributeMap().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> Dumpable.named(e.getKey(), e.getValue())).toArray(Object[]::new);
        Dumpable.dumpObjects(out, indent, _name + " size=" + array.length, array);
    }
}