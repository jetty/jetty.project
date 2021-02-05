package org.eclipse.jetty.http3.qpack.table;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http3.qpack.QpackContext;

public class DynamicTable
{
    public static final int FIRST_INDEX = StaticTable.STATIC_SIZE + 1;
    private int _maxDynamicTableSizeInBytes;
    private int _dynamicTableSizeInBytes;

    private final Map<HttpField, Entry> _fieldMap = new HashMap<>();
    private final Map<String, Entry> _nameMap = new HashMap<>();
    private final int _growby;

    private Entry[] _entries;
    private int _numEntries;
    private int _offset;

    public DynamicTable(int maxSize)
    {
        _maxDynamicTableSizeInBytes = maxSize;
        int initCapacity = 10 + maxSize / (32 + 10 + 10);
        _entries = new Entry[initCapacity];
        _growby = initCapacity;
    }

    public Entry add(Entry entry)
    {
        int size = entry.getSize();
        if (size > _maxDynamicTableSizeInBytes)
        {
            if (QpackContext.LOG.isDebugEnabled())
                QpackContext.LOG.debug(String.format("HdrTbl[%x] !added size %d>%d", hashCode(), size, _maxDynamicTableSizeInBytes));
            evictAll();
            return null;
        }
        _dynamicTableSizeInBytes += size;

        if (_numEntries == _entries.length)
        {
            Entry[] entries = new Entry[_entries.length + _growby];
            for (int i = 0; i < _numEntries; i++)
            {
                int slot = (_offset + i) % _entries.length;
                entries[i] = _entries[slot];
                entries[i].setIndex(i);
            }
            _entries = entries;
            _offset = 0;
        }
        int slot = (_numEntries++ + _offset) % _entries.length;
        _entries[slot] = entry;
        entry.setIndex(slot);

        _fieldMap.put(entry.getHttpField(), entry);
        _nameMap.put(entry.getHttpField().getLowerCaseName(), entry);

        if (QpackContext.LOG.isDebugEnabled())
            QpackContext.LOG.debug(String.format("HdrTbl[%x] added %s", hashCode(), entry));
        evict();
        return entry;
    }

    public int index(Entry entry)
    {
        return StaticTable.STATIC_SIZE + _numEntries - (entry.getIndex() - _offset + _entries.length) % _entries.length;
    }

    public Entry get(int index)
    {
        int d = index - StaticTable.STATIC_SIZE - 1;
        if (d < 0 || d >= _numEntries)
            return null;
        int slot = (_offset + _numEntries - d - 1) % _entries.length;
        return _entries[slot];
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
        return _dynamicTableSizeInBytes;
    }

    public int getMaxSize()
    {
        return _maxDynamicTableSizeInBytes;
    }

    public int getNumEntries()
    {
        return _numEntries;
    }

    public void setCapacity(int capacity)
    {
        if (QpackContext.LOG.isDebugEnabled())
            QpackContext.LOG.debug(String.format("HdrTbl[%x] resized max=%d->%d", hashCode(), _maxDynamicTableSizeInBytes, capacity));
        _maxDynamicTableSizeInBytes = capacity;
        evict();
    }

    private void evict()
    {
        while (_dynamicTableSizeInBytes > _maxDynamicTableSizeInBytes)
        {
            Entry entry = _entries[_offset];
            _entries[_offset] = null;
            _offset = (_offset + 1) % _entries.length;
            _numEntries--;
            if (QpackContext.LOG.isDebugEnabled())
                QpackContext.LOG.debug(String.format("HdrTbl[%x] evict %s", hashCode(), entry));
            _dynamicTableSizeInBytes -= entry.getSize();
            entry.setIndex(-1);
            _fieldMap.remove(entry.getHttpField());
            String lc = entry.getHttpField().getLowerCaseName();
            if (entry == _nameMap.get(lc))
                _nameMap.remove(lc);
        }
        if (QpackContext.LOG.isDebugEnabled())
            QpackContext.LOG.debug(String.format("HdrTbl[%x] entries=%d, size=%d, max=%d", hashCode(), getNumEntries(), _dynamicTableSizeInBytes, _maxDynamicTableSizeInBytes));
    }

    private void evictAll()
    {
        if (QpackContext.LOG.isDebugEnabled())
            QpackContext.LOG.debug(String.format("HdrTbl[%x] evictAll", hashCode()));
        if (getNumEntries() > 0)
        {
            _fieldMap.clear();
            _nameMap.clear();
            _offset = 0;
            _numEntries = 0;
            _dynamicTableSizeInBytes = 0;
            Arrays.fill(_entries, null);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{entries=%d,size=%d,max=%d}", getClass().getSimpleName(), hashCode(), getNumEntries(), _dynamicTableSizeInBytes, _maxDynamicTableSizeInBytes);
    }
}
