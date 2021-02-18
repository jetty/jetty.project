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
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http3.qpack.QpackContext;
import org.eclipse.jetty.http3.qpack.QpackException;

public class DynamicTable
{
    public static final int FIRST_INDEX = StaticTable.STATIC_SIZE + 1;
    private int _maxCapacity;
    private int _capacity;
    private int _absoluteIndex;

    private final Map<HttpField, Entry> _fieldMap = new HashMap<>();
    private final Map<String, Entry> _nameMap = new HashMap<>();
    private final List<Entry> _entries = new ArrayList<>();

    private final Map<Integer, StreamInfo> streamInfoMap = new HashMap<>();

    public static class StreamInfo
    {
        private int streamId;
        private final List<Integer> referencedEntries = new ArrayList<>();
        private int potentiallyBlockedStreams = 0;
    }

    public DynamicTable()
    {
    }

    public Entry add(Entry entry)
    {
        evict();

        int size = entry.getSize();
        if (size + _capacity > _maxCapacity)
        {
            if (QpackContext.LOG.isDebugEnabled())
                QpackContext.LOG.debug(String.format("HdrTbl[%x] !added size %d>%d", hashCode(), size, _maxCapacity));
            return null;
        }
        _capacity += size;

        // Set the Entries absolute index which will never change.
        entry.setIndex(_absoluteIndex++);
        _entries.add(0, entry);
        _fieldMap.put(entry.getHttpField(), entry);
        _nameMap.put(entry.getHttpField().getLowerCaseName(), entry);

        if (QpackContext.LOG.isDebugEnabled())
            QpackContext.LOG.debug(String.format("HdrTbl[%x] added %s", hashCode(), entry));
        return entry;
    }

    public int index(Entry entry)
    {
        // TODO: should we improve efficiency of this by storing in the entry itself.
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

    public int getSize()
    {
        return _capacity;
    }

    public int getMaxSize()
    {
        return _maxCapacity;
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
            QpackContext.LOG.debug(String.format("HdrTbl[%x] resized max=%d->%d", hashCode(), _maxCapacity, capacity));
        _maxCapacity = capacity;
        evict();
    }

    private boolean canReference(Entry entry)
    {
        int evictionThreshold = getEvictionThreshold();
        int lowestReferencableIndex = -1;
        int remainingCapacity = _capacity;
        for (int i = 0; i < _entries.size(); i++)
        {
            if (remainingCapacity <= evictionThreshold)
            {
                lowestReferencableIndex = i;
                break;
            }

            remainingCapacity -= _entries.get(i).getSize();
        }

        return index(entry) >= lowestReferencableIndex;
    }

    private void evict()
    {
        int evictionThreshold = getEvictionThreshold();
        for (Entry e : _entries)
        {
            // We only evict when the table is getting full.
            if (_capacity < evictionThreshold)
                return;

            // We can only evict if there are no references outstanding to this entry.
            if (e.getReferenceCount() != 0)
                return;

            // Remove this entry.
            if (QpackContext.LOG.isDebugEnabled())
                QpackContext.LOG.debug(String.format("HdrTbl[%x] evict %s", hashCode(), e));

            Entry removedEntry = _entries.remove(0);
            if (removedEntry != e)
                throw new IllegalStateException("Corruption in DynamicTable");
            _fieldMap.remove(e.getHttpField());
            String name = e.getHttpField().getLowerCaseName();
            if (e == _nameMap.get(name))
                _nameMap.remove(name);

            _capacity -= e.getSize();
        }

        if (QpackContext.LOG.isDebugEnabled())
            QpackContext.LOG.debug(String.format("HdrTbl[%x] entries=%d, size=%d, max=%d", hashCode(), getNumEntries(), _capacity, _maxCapacity));
    }

    private int getEvictionThreshold()
    {
        return _maxCapacity * 3 / 4;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{entries=%d,size=%d,max=%d}", getClass().getSimpleName(), hashCode(), getNumEntries(), _capacity, _maxCapacity);
    }
}
