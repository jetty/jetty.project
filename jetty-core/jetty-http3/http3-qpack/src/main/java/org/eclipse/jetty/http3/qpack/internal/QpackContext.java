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

package org.eclipse.jetty.http3.qpack.internal;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http3.qpack.internal.table.DynamicTable;
import org.eclipse.jetty.http3.qpack.internal.table.Entry;
import org.eclipse.jetty.http3.qpack.internal.table.StaticTable;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QPACK - Header Compression for HTTP/2
 * <p>This class maintains the compression context for a single HTTP/2
 * connection. Specifically it holds the static and dynamic Header Field Tables
 * and the associated sizes and limits.
 * </p>
 * <p>It is compliant with draft 11 of the specification</p>
 */
public class QpackContext
{
    private static final Logger LOG = LoggerFactory.getLogger(QpackContext.class);
    private static final StaticTable __staticTable = new StaticTable();
    private final DynamicTable _dynamicTable;

    public QpackContext()
    {
        _dynamicTable = new DynamicTable();
    }

    public DynamicTable getDynamicTable()
    {
        return _dynamicTable;
    }

    public static StaticTable getStaticTable()
    {
        return __staticTable;
    }

    public Entry get(HttpField field)
    {
        Entry entry = _dynamicTable.get(field);
        if (entry == null)
            entry = __staticTable.get(field);
        return entry;
    }

    public Entry get(String name)
    {
        Entry entry = __staticTable.get(name);
        if (entry != null)
            return entry;
        return _dynamicTable.get(StringUtil.asciiToLowerCase(name));
    }

    public Entry get(int index)
    {
        if (index <= StaticTable.STATIC_SIZE)
            return __staticTable.get(index);

        return _dynamicTable.get(index);
    }

    /**
     * Get the relative Index of an entry.
     * @param entry the entry to get the index of.
     * @return the relative index of the entry.
     */
    public int indexOf(Entry entry)
    {
        if (entry.isStatic())
            return entry.getIndex();

        return _dynamicTable.index(entry);
    }
}
