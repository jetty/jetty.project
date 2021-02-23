//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http3.qpack.table.DynamicTable;
import org.eclipse.jetty.http3.qpack.table.Entry;
import org.eclipse.jetty.http3.qpack.table.StaticTable;
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
    public static final Logger LOG = LoggerFactory.getLogger(QpackContext.class);
    private static final StaticTable __staticTable = new StaticTable();
    private final DynamicTable _dynamicTable;

    public QpackContext()
    {
        _dynamicTable = new DynamicTable();
        if (LOG.isDebugEnabled())
            LOG.debug(String.format("HdrTbl[%x] created", hashCode()));
    }

    @Deprecated
    public QpackContext(int maxDynamicTableSize)
    {
        this();
        _dynamicTable.setCapacity(maxDynamicTableSize);
    }

    public DynamicTable getDynamicTable()
    {
        return _dynamicTable;
    }

    public StaticTable getStaticTable()
    {
        return __staticTable;
    }

    public void resize(int newMaxDynamicTableSize)
    {
        _dynamicTable.setCapacity(newMaxDynamicTableSize);
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

    public Entry get(HttpHeader header)
    {
        Entry e = __staticTable.get(header);
        if (e == null)
            return get(header.asString());
        return e;
    }

    public Entry add(HttpField field)
    {
        Entry entry = new Entry(field);
        _dynamicTable.add(entry);
        return entry;
    }

    public boolean canReference(Entry entry)
    {
        if (entry.isStatic())
            return true;

        return _dynamicTable.canReference(entry);
    }

    /**
     * @return Current dynamic table size in entries
     */
    public int getNumEntries()
    {
        return _dynamicTable.getNumEntries();
    }

    /**
     * @return Current Dynamic table size in Octets
     */
    public int getDynamicTableSize()
    {
        return _dynamicTable.getSize();
    }

    /**
     * @return Max Dynamic table size in Octets
     */
    public int getMaxDynamicTableSize()
    {
        return _dynamicTable.getCapacity();
    }

    /**
     * @return index of entry in COMBINED address space (QPACK has separate address spaces for dynamic and static tables).
     */
    public int index(Entry entry)
    {
        if (entry.isStatic())
            return entry.getIndex();
        return _dynamicTable.index(entry);
    }

    /**
     * @return index of entry in the static table or 0 if not in the table (I guess the entries start from 1 not 0 unlike QPACK).
     */
    public static int staticIndex(HttpHeader header)
    {
        if (header == null)
            return 0;
        Entry entry = __staticTable.get(header.asString());
        if (entry == null)
            return 0;
        return entry.getIndex();
    }
}
