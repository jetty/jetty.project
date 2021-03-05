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

package org.eclipse.jetty.http3.qpack.table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http3.qpack.QpackContext;
import org.eclipse.jetty.http3.qpack.QpackException;

public class DynamicTable implements Iterable<Entry>
{
    private final Map<HttpField, Entry> _fieldMap = new HashMap<>();
    private final Map<String, Entry> _nameMap = new HashMap<>();
    private final List<Entry> _entries = new ArrayList<>();

    private int _capacity;
    private int _size;
    private int _absoluteIndex;
    private int _drainingIndex;

    public void add(Entry entry)
    {
        int entrySize = entry.getSize();
        if (entrySize + _size > _capacity)
            throw new IllegalStateException("No available space");
        _size += entrySize;

        // Set the Entries absolute index which will never change.
        entry.setIndex(_absoluteIndex++);
        _entries.add(entry);
        _fieldMap.put(entry.getHttpField(), entry);
        _nameMap.put(entry.getHttpField().getLowerCaseName(), entry);

        // Update the draining index.
        _drainingIndex = getDrainingIndex();
    }

    public int index(Entry entry)
    {
        return _entries.indexOf(entry);
    }

    public Entry getAbsolute(int absoluteIndex) throws QpackException
    {
        if (absoluteIndex < 0)
            throw new QpackException.CompressionException("Invalid Index");
        return _entries.stream().filter(e -> e.getIndex() == absoluteIndex).findFirst().orElse(null);
    }

    public Entry get(int index)
    {
        return _entries.get(index);
    }

    public Entry get(String name)
    {
        return _nameMap.get(name);
    }

    public Entry get(HttpField field)
    {
        return _fieldMap.get(field);
    }

    public int getBase()
    {
        if (_entries.size() == 0)
            return _absoluteIndex;
        return _entries.get(0).getIndex();
    }

    public int getSize()
    {
        return _size;
    }

    public int getCapacity()
    {
        return _capacity;
    }

    public int getSpace()
    {
        return _capacity - _size;
    }

    public int getNumEntries()
    {
        return _entries.size();
    }

    public int getInsertCount()
    {
        return _absoluteIndex;
    }

    public void setCapacity(int capacity)
    {
        if (QpackContext.LOG.isDebugEnabled())
            QpackContext.LOG.debug(String.format("HdrTbl[%x] resized max=%d->%d", hashCode(), _capacity, capacity));
        _capacity = capacity;
    }

    public boolean canReference(Entry entry)
    {
        return entry.getIndex() >= _drainingIndex;
    }

    public void evict()
    {
        for (Entry entry : _entries)
        {
            if (entry.getIndex() >= _drainingIndex)
                return;

            // We can only evict if there are no references outstanding to this entry.
            if (entry.getReferenceCount() != 0)
                return;

            // Evict the entry from the DynamicTable.
            _size -= entry.getSize();
            if (entry != _entries.remove(0))
                throw new IllegalStateException("Corruption in DynamicTable");

            HttpField httpField = entry.getHttpField();
            if (entry == _fieldMap.get(httpField))
                _fieldMap.remove(httpField);

            String name = httpField.getLowerCaseName();
            if (entry == _nameMap.get(name))
                _nameMap.remove(name);
        }
    }

    /**
     * Entries with indexes lower than the draining index should not be referenced.
     * This allows these entries to eventually be evicted as no more references will be made to them.
     *
     * @return the smallest absolute index that is allowed to be referenced.
     */
    protected int getDrainingIndex()
    {
        int evictionThreshold = _capacity * 3 / 4;

        // We can reference all entries if our current size is below the eviction threshold.
        if (_size <= evictionThreshold)
            return 0;

        // Entries which cause us to exceed the evictionThreshold should not be referenced.
        int remainingCapacity = _size;
        for (Entry entry : _entries)
        {
            remainingCapacity -= entry.getSize();

            // If evicting this and everything under would bring us under the eviction threshold,
            // then we should not reference this entry or anything below.
            if (remainingCapacity <= evictionThreshold)
                return entry.getIndex() + 1;
        }

        throw new IllegalStateException();
    }

    @Override
    public Iterator<Entry> iterator()
    {
        return _entries.iterator();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{entries=%d,size=%d,max=%d}", getClass().getSimpleName(), hashCode(), getNumEntries(), _size, _capacity);
    }
}
