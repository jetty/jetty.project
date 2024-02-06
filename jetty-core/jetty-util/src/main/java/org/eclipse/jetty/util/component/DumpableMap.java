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
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class DumpableMap implements Dumpable
{
    private final String _name;
    private final Map<?, ?> _map;

    public DumpableMap(String name, Map<?, ?> map)
    {
        _name = name;
        _map = map;
    }

    public static DumpableMap from(String name, Map<?, ?> items)
    {
        return from(name, items, false);
    }

    public static DumpableMap from(String name, Map<?, ?> items, boolean ordered)
    {
        items = items == null ? Collections.emptyMap() : items;
        if (ordered && !(items instanceof SortedMap<?, ?>))
            items = new TreeMap<>(items);
        return new DumpableMap(name, items);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Object[] array = _map == null ? null : _map.entrySet().toArray();
        Dumpable.dumpObjects(out, indent, _name + " size=" + (array == null ? 0 : array.length), array);
    }
}

