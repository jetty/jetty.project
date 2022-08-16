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

package org.eclipse.jetty.http2.hpack;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http2.hpack.internal.Huffman;
import org.eclipse.jetty.http2.hpack.internal.NBitInteger;
import org.eclipse.jetty.http2.hpack.internal.StaticTableHttpField;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HPACK - Header Compression for HTTP/2
 * <p>This class maintains the compression context for a single HTTP/2
 * connection. Specifically it holds the static and dynamic Header Field Tables
 * and the associated sizes and limits.
 * </p>
 * <p>It is compliant with draft 11 of the specification</p>
 */
public class HpackContext
{
    private static final Logger LOG = LoggerFactory.getLogger(HpackContext.class);
    private static final String EMPTY = "";
    public static final String[][] STATIC_TABLE =
        {
            {null, null},
            /* 1  */ {":authority", EMPTY},
            /* 2  */ {":method", "GET"},
            /* 3  */ {":method", "POST"},
            /* 4  */ {":path", "/"},
            /* 5  */ {":path", "/index.html"},
            /* 6  */ {":scheme", "http"},
            /* 7  */ {":scheme", "https"},
            /* 8  */ {":status", "200"},
            /* 9  */ {":status", "204"},
            /* 10 */ {":status", "206"},
            /* 11 */ {":status", "304"},
            /* 12 */ {":status", "400"},
            /* 13 */ {":status", "404"},
            /* 14 */ {":status", "500"},
            /* 15 */ {"accept-charset", EMPTY},
            /* 16 */ {"accept-encoding", "gzip, deflate"},
            /* 17 */ {"accept-language", EMPTY},
            /* 18 */ {"accept-ranges", EMPTY},
            /* 19 */ {"accept", EMPTY},
            /* 20 */ {"access-control-allow-origin", EMPTY},
            /* 21 */ {"age", EMPTY},
            /* 22 */ {"allow", EMPTY},
            /* 23 */ {"authorization", EMPTY},
            /* 24 */ {"cache-control", EMPTY},
            /* 25 */ {"content-disposition", EMPTY},
            /* 26 */ {"content-encoding", EMPTY},
            /* 27 */ {"content-language", EMPTY},
            /* 28 */ {"content-length", EMPTY},
            /* 29 */ {"content-location", EMPTY},
            /* 30 */ {"content-range", EMPTY},
            /* 31 */ {"content-type", EMPTY},
            /* 32 */ {"cookie", EMPTY},
            /* 33 */ {"date", EMPTY},
            /* 34 */ {"etag", EMPTY},
            /* 35 */ {"expect", EMPTY},
            /* 36 */ {"expires", EMPTY},
            /* 37 */ {"from", EMPTY},
            /* 38 */ {"host", EMPTY},
            /* 39 */ {"if-match", EMPTY},
            /* 40 */ {"if-modified-since", EMPTY},
            /* 41 */ {"if-none-match", EMPTY},
            /* 42 */ {"if-range", EMPTY},
            /* 43 */ {"if-unmodified-since", EMPTY},
            /* 44 */ {"last-modified", EMPTY},
            /* 45 */ {"link", EMPTY},
            /* 46 */ {"location", EMPTY},
            /* 47 */ {"max-forwards", EMPTY},
            /* 48 */ {"proxy-authenticate", EMPTY},
            /* 49 */ {"proxy-authorization", EMPTY},
            /* 50 */ {"range", EMPTY},
            /* 51 */ {"referer", EMPTY},
            /* 52 */ {"refresh", EMPTY},
            /* 53 */ {"retry-after", EMPTY},
            /* 54 */ {"server", EMPTY},
            /* 55 */ {"set-cookie", EMPTY},
            /* 56 */ {"strict-transport-security", EMPTY},
            /* 57 */ {"transfer-encoding", EMPTY},
            /* 58 */ {"user-agent", EMPTY},
            /* 59 */ {"vary", EMPTY},
            /* 60 */ {"via", EMPTY},
            /* 61 */ {"www-authenticate", EMPTY}
        };

    private static final Map<HttpField, Entry> __staticFieldMap = new HashMap<>();
    private static final Index<StaticEntry> __staticNameMap;
    private static final StaticEntry[] __staticTableByHeader = new StaticEntry[HttpHeader.values().length];
    private static final StaticEntry[] __staticTable = new StaticEntry[STATIC_TABLE.length];
    public static final int STATIC_SIZE = STATIC_TABLE.length - 1;

    static
    {
        Index.Builder<StaticEntry> staticNameMapBuilder = new Index.Builder<StaticEntry>().caseSensitive(false);
        Set<String> added = new HashSet<>();
        for (int i = 1; i < STATIC_TABLE.length; i++)
        {
            StaticEntry entry = null;

            String name = STATIC_TABLE[i][0];
            String value = STATIC_TABLE[i][1];
            HttpHeader header = HttpHeader.CACHE.get(name);
            if (header != null && value != null)
            {
                switch (header)
                {
                    case C_METHOD:
                    {

                        HttpMethod method = HttpMethod.CACHE.get(value);
                        if (method != null)
                            entry = new StaticEntry(i, new StaticTableHttpField(header, name, value, method));
                        break;
                    }

                    case C_SCHEME:
                    {

                        HttpScheme scheme = HttpScheme.CACHE.get(value);
                        if (scheme != null)
                            entry = new StaticEntry(i, new StaticTableHttpField(header, name, value, scheme));
                        break;
                    }

                    case C_STATUS:
                    {
                        entry = new StaticEntry(i, new StaticTableHttpField(header, name, value, value));
                        break;
                    }

                    default:
                        break;
                }
            }

            if (entry == null)
                entry = new StaticEntry(i, header == null ? new HttpField(STATIC_TABLE[i][0], value) : new HttpField(header, name, value));

            __staticTable[i] = entry;

            if (entry._field.getValue() != null)
                __staticFieldMap.put(entry._field, entry);

            if (!added.contains(entry._field.getName()))
            {
                added.add(entry._field.getName());
                staticNameMapBuilder.with(entry._field.getName(), entry);
            }
        }
        __staticNameMap = staticNameMapBuilder.build();

        for (HttpHeader h : HttpHeader.values())
        {
            StaticEntry entry = __staticNameMap.get(h.asString());
            if (entry != null)
                __staticTableByHeader[h.ordinal()] = entry;
        }
    }

    private int _maxDynamicTableSizeInBytes;
    private int _dynamicTableSizeInBytes;
    private final DynamicTable _dynamicTable;
    private final Map<HttpField, Entry> _fieldMap = new HashMap<>();
    private final Map<String, Entry> _nameMap = new HashMap<>();

    HpackContext(int maxDynamicTableSize)
    {
        _maxDynamicTableSizeInBytes = maxDynamicTableSize;
        int guesstimateEntries = 10 + maxDynamicTableSize / (32 + 10 + 10);
        _dynamicTable = new DynamicTable(guesstimateEntries);
        if (LOG.isDebugEnabled())
            LOG.debug(String.format("HdrTbl[%x] created max=%d", hashCode(), maxDynamicTableSize));
    }

    public void resize(int newMaxDynamicTableSize)
    {
        if (LOG.isDebugEnabled())
            LOG.debug(String.format("HdrTbl[%x] resized max=%d->%d", hashCode(), _maxDynamicTableSizeInBytes, newMaxDynamicTableSize));
        _maxDynamicTableSizeInBytes = newMaxDynamicTableSize;
        _dynamicTable.evict();
    }

    public Entry get(HttpField field)
    {
        Entry entry = _fieldMap.get(field);
        if (entry == null)
            entry = __staticFieldMap.get(field);
        return entry;
    }

    public Entry get(String name)
    {
        Entry entry = __staticNameMap.get(name);
        if (entry != null)
            return entry;
        return _nameMap.get(StringUtil.asciiToLowerCase(name));
    }

    public Entry get(int index)
    {
        if (index <= STATIC_SIZE)
            return __staticTable[index];

        return _dynamicTable.get(index);
    }

    public Entry get(HttpHeader header)
    {
        Entry e = __staticTableByHeader[header.ordinal()];
        if (e == null)
            return get(header.asString());
        return e;
    }

    public static Entry getStatic(HttpHeader header)
    {
        return __staticTableByHeader[header.ordinal()];
    }

    public Entry add(HttpField field)
    {
        Entry entry = new Entry(field);
        int size = entry.getSize();
        if (size > _maxDynamicTableSizeInBytes)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(String.format("HdrTbl[%x] !added size %d>%d", hashCode(), size, _maxDynamicTableSizeInBytes));
            _dynamicTable.evictAll();
            return null;
        }
        _dynamicTableSizeInBytes += size;
        _dynamicTable.add(entry);
        _fieldMap.put(field, entry);
        _nameMap.put(field.getLowerCaseName(), entry);

        if (LOG.isDebugEnabled())
            LOG.debug(String.format("HdrTbl[%x] added %s", hashCode(), entry));
        _dynamicTable.evict();
        return entry;
    }

    /**
     * @return Current dynamic table size in entries
     */
    public int size()
    {
        return _dynamicTable.size();
    }

    /**
     * @return Current Dynamic table size in Octets
     */
    public int getDynamicTableSize()
    {
        return _dynamicTableSizeInBytes;
    }

    /**
     * @return Max Dynamic table size in Octets
     */
    public int getMaxDynamicTableSize()
    {
        return _maxDynamicTableSizeInBytes;
    }

    public int index(Entry entry)
    {
        if (entry._slot < 0)
            return 0;
        if (entry.isStatic())
            return entry._slot;

        return _dynamicTable.index(entry);
    }

    public static int staticIndex(HttpHeader header)
    {
        if (header == null)
            return 0;
        Entry entry = __staticNameMap.get(header.asString());
        if (entry == null)
            return 0;
        return entry._slot;
    }

    @Override
    public String toString()
    {
        return String.format("HpackContext@%x{entries=%d,size=%d,max=%d}", hashCode(), _dynamicTable.size(), _dynamicTableSizeInBytes, _maxDynamicTableSizeInBytes);
    }

    private class DynamicTable
    {
        Entry[] _entries;
        int _size;
        int _offset;
        int _growby;

        private DynamicTable(int initCapacity)
        {
            _entries = new Entry[initCapacity];
            _growby = initCapacity;
        }

        public void add(Entry entry)
        {
            if (_size == _entries.length)
            {
                Entry[] entries = new Entry[_entries.length + _growby];
                for (int i = 0; i < _size; i++)
                {
                    int slot = (_offset + i) % _entries.length;
                    entries[i] = _entries[slot];
                    entries[i]._slot = i;
                }
                _entries = entries;
                _offset = 0;
            }
            int slot = (_size++ + _offset) % _entries.length;
            _entries[slot] = entry;
            entry._slot = slot;
        }

        public int index(Entry entry)
        {
            return STATIC_SIZE + _size - (entry._slot - _offset + _entries.length) % _entries.length;
        }

        public Entry get(int index)
        {
            int d = index - STATIC_SIZE - 1;
            if (d < 0 || d >= _size)
                return null;
            int slot = (_offset + _size - d - 1) % _entries.length;
            return _entries[slot];
        }

        public int size()
        {
            return _size;
        }

        private void evict()
        {
            while (_dynamicTableSizeInBytes > _maxDynamicTableSizeInBytes)
            {
                Entry entry = _entries[_offset];
                _entries[_offset] = null;
                _offset = (_offset + 1) % _entries.length;
                _size--;
                if (LOG.isDebugEnabled())
                    LOG.debug(String.format("HdrTbl[%x] evict %s", HpackContext.this.hashCode(), entry));
                _dynamicTableSizeInBytes -= entry.getSize();
                entry._slot = -1;
                _fieldMap.remove(entry.getHttpField());
                String lc = entry.getHttpField().getLowerCaseName();
                if (entry == _nameMap.get(lc))
                    _nameMap.remove(lc);
            }
            if (LOG.isDebugEnabled())
                LOG.debug(String.format("HdrTbl[%x] entries=%d, size=%d, max=%d", HpackContext.this.hashCode(), _dynamicTable.size(), _dynamicTableSizeInBytes, _maxDynamicTableSizeInBytes));
        }

        private void evictAll()
        {
            if (LOG.isDebugEnabled())
                LOG.debug(String.format("HdrTbl[%x] evictAll", HpackContext.this.hashCode()));
            if (size() > 0)
            {
                _fieldMap.clear();
                _nameMap.clear();
                _offset = 0;
                _size = 0;
                _dynamicTableSizeInBytes = 0;
                Arrays.fill(_entries, null);
            }
        }
    }

    public static class Entry
    {
        final HttpField _field;
        int _slot; // The index within it's array

        Entry()
        {
            _slot = -1;
            _field = null;
        }

        Entry(HttpField field)
        {
            _field = field;
        }

        public int getSize()
        {
            String value = _field.getValue();
            return 32 + _field.getName().length() + (value == null ? 0 : value.length());
        }

        public HttpField getHttpField()
        {
            return _field;
        }

        public boolean isStatic()
        {
            return false;
        }

        public byte[] getStaticHuffmanValue()
        {
            return null;
        }

        @Override
        public String toString()
        {
            return String.format("{%s,%d,%s,%x}", isStatic() ? "S" : "D", _slot, _field, hashCode());
        }
    }

    public static class StaticEntry extends Entry
    {
        private final byte[] _huffmanValue;
        private final byte _encodedField;

        StaticEntry(int index, HttpField field)
        {
            super(field);
            _slot = index;
            String value = field.getValue();
            if (value != null && value.length() > 0)
            {
                int huffmanLen = Huffman.octetsNeeded(value);
                if (huffmanLen < 0)
                    throw new IllegalStateException("bad value");
                int lenLen = NBitInteger.octectsNeeded(7, huffmanLen);
                _huffmanValue = new byte[1 + lenLen + huffmanLen];
                ByteBuffer buffer = ByteBuffer.wrap(_huffmanValue);

                // Indicate Huffman
                buffer.put((byte)0x80);
                // Add huffman length
                NBitInteger.encode(buffer, 7, huffmanLen);
                // Encode value
                Huffman.encode(buffer, value);
            }
            else
                _huffmanValue = null;

            _encodedField = (byte)(0x80 | index);
        }

        @Override
        public boolean isStatic()
        {
            return true;
        }

        @Override
        public byte[] getStaticHuffmanValue()
        {
            return _huffmanValue;
        }

        public byte getEncodedField()
        {
            return _encodedField;
        }
    }
}
