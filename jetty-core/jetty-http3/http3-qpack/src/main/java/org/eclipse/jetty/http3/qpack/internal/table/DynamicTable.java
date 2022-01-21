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

package org.eclipse.jetty.http3.qpack.internal.table;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.util.component.Dumpable;

public class DynamicTable implements Iterable<Entry>, Dumpable
{
    private final Map<HttpField, Entry> _fieldMap = new HashMap<>();
    private final Map<String, Entry> _nameMap = new HashMap<>();
    private final List<Entry> _entries = new ArrayList<>();

    private int _capacity;
    private int _size;
    private int _absoluteIndex;
    private int _drainingIndex;

    /**
     * Add an entry into the Dynamic Table. This will throw if it is not possible to insert this entry.
     * Use {@link #canInsert(HttpField)} to check whether it is possible to insert this entry.
     * @param entry the entry to insert.
     */
    public void add(Entry entry)
    {
        // Evict entries until there is space for the new entry.
        Iterator<Entry> iterator = _entries.iterator();
        int entrySize = entry.getSize();
        while (getSpace() < entrySize)
        {
            if (!iterator.hasNext())
                throw new IllegalStateException("not enough space in dynamic table to add entry");

            Entry e = iterator.next();
            if (e.getReferenceCount() != 0)
                throw new IllegalStateException("cannot evict entry that is still referenced");

            // Evict the entry from the DynamicTable.
            _size -= e.getSize();
            iterator.remove();

            HttpField httpField = e.getHttpField();
            if (e == _fieldMap.get(httpField))
                _fieldMap.remove(httpField);

            String name = httpField.getLowerCaseName();
            if (e == _nameMap.get(name))
                _nameMap.remove(name);
        }

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

    /**
     * Is there enough room to insert a new entry into the Dynamic Table, possibly evicting unreferenced entries.
     * @param field the HttpField to insert into the table.
     * @return if an entry with this HttpField can be inserted.
     */
    public boolean canInsert(HttpField field)
    {
        int availableSpace = getSpace();
        int requiredSpace = Entry.getSize(field);

        if (availableSpace >= requiredSpace)
            return true;

        if (requiredSpace > _capacity)
            return false;

        // Could we potentially evict enough space to insert this new field.
        for (Entry entry : _entries)
        {
            if (entry.getReferenceCount() != 0)
                return false;

            availableSpace += entry.getSize();
            if (availableSpace >= requiredSpace)
                return true;
        }

        return false;
    }

    /**
     * Get the relative index of an entry in the Dynamic Table.
     * @param entry the entry to find the relative index of.
     * @return the relative index of this entry.
     */
    public int index(Entry entry)
    {
        if (_entries.isEmpty())
            throw new IllegalArgumentException("Invalid Index");

        Entry firstEntry = _entries.get(0);
        int index = entry.getIndex() - firstEntry.getIndex();
        if (index >= _entries.size())
            throw new IllegalArgumentException("Invalid Index");

        return index;
    }

    /**
     * Get an entry from the Dynamic table given an absolute index.
     * @param absoluteIndex the absolute index of the entry in the table.
     * @return the entry with the absolute index.
     */
    public Entry getAbsolute(int absoluteIndex)
    {
        if (absoluteIndex < 0)
            throw new IllegalArgumentException("Invalid Index");

        if (_entries.isEmpty())
            throw new IllegalArgumentException("Invalid Index");

        Entry firstEntry = _entries.get(0);
        int index = absoluteIndex - firstEntry.getIndex();
        if (index < 0 || index >= _entries.size())
            throw new IllegalArgumentException("Invalid Index " + index);

        return _entries.get(index);
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
        _capacity = capacity;

        Iterator<Entry> iterator = _entries.iterator();
        while (_size > _capacity)
        {
            if (!iterator.hasNext())
                throw new IllegalStateException();

            Entry entry = iterator.next();
            if (entry.getReferenceCount() != 0)
                return;

            // Evict the entry from the DynamicTable.
            _size -= entry.getSize();
            iterator.remove();

            HttpField httpField = entry.getHttpField();
            if (entry == _fieldMap.get(httpField))
                _fieldMap.remove(httpField);

            String name = httpField.getLowerCaseName();
            if (entry == _nameMap.get(name))
                _nameMap.remove(name);
        }
    }

    public boolean canReference(Entry entry)
    {
        return entry.getIndex() >= _drainingIndex;
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
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, _entries);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{entries=%d,size=%d,max=%d}", getClass().getSimpleName(), hashCode(), getNumEntries(), _size, _capacity);
    }
}
