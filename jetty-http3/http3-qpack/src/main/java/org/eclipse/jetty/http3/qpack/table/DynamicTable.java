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
    public static final int FIRST_INDEX = StaticTable.STATIC_SIZE + 1;
    private int _capacity;
    private int _size;
    private int _absoluteIndex;

    private final Map<HttpField, Entry> _fieldMap = new HashMap<>();
    private final Map<String, Entry> _nameMap = new HashMap<>();
    private final List<Entry> _entries = new ArrayList<>();

    private final Map<Integer, StreamInfo> streamInfoMap = new HashMap<>();

    public static class StreamInfo
    {
        private int streamId;
        private final List<Integer> referencedEntries = new ArrayList<>();
        private int potentiallyBlockedStreams;
    }

    public DynamicTable()
    {
    }

    public void add(Entry entry)
    {
        evict();

        int entrySize = entry.getSize();
        if (entrySize + _size > _capacity)
            throw new IllegalStateException("No available space");
        _size += entrySize;

        // Set the Entries absolute index which will never change.
        entry.setIndex(_absoluteIndex++);
        _entries.add(entry);
        _fieldMap.put(entry.getHttpField(), entry);
        _nameMap.put(entry.getHttpField().getLowerCaseName(), entry);

        if (QpackContext.LOG.isDebugEnabled())
            QpackContext.LOG.debug(String.format("HdrTbl[%x] added %s", hashCode(), entry));
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
        evict();
    }

    public boolean canReference(Entry entry)
    {
        int evictionThreshold = getEvictionThreshold();
        int lowestReferencableIndex = -1;
        int remainingCapacity = _size;
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
            if (_size < evictionThreshold)
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

            _size -= e.getSize();
        }

        if (QpackContext.LOG.isDebugEnabled())
            QpackContext.LOG.debug(String.format("HdrTbl[%x] entries=%d, size=%d, max=%d", hashCode(), getNumEntries(), _size, _capacity));
    }

    private int getEvictionThreshold()
    {
        return _capacity * 3 / 4;
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
