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

package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

public class DumpableCollection implements Dumpable
{
    private final String _name;
    private final Collection<?> _collection;

    public DumpableCollection(String name, Collection<?> collection)
    {
        _name = name;
        _collection = collection;
    }

    public static DumpableCollection fromArray(String name, Object[] array)
    {
        return new DumpableCollection(name, array == null ? Collections.emptyList() : Arrays.asList(array));
    }

    public static DumpableCollection from(String name, Object... items)
    {
        return new DumpableCollection(name, items == null ? Collections.emptyList() : Arrays.asList(items));
    }

    public static DumpableCollection from(String name, Collection<?> collection)
    {
        return new DumpableCollection(name, collection);
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Object[] array = (_collection == null ? null : _collection.toArray());
        Dumpable.dumpObjects(out, indent, _name + " size=" + (array == null ? 0 : array.length), array);
    }
}
