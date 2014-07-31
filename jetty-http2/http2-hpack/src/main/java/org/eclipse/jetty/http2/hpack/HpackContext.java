//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.hpack;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HpackContext
{
    public static final Logger LOG = Log.getLogger(HpackContext.class);
    
    public static final String[][] STATIC_TABLE = 
    {
        {null,null},
        /* 1  */ {":authority",null},
        /* 2  */ {":method","GET"},
        /* 3  */ {":method","POST"},
        /* 4  */ {":path","/"},
        /* 5  */ {":path","/index.html"},
        /* 6  */ {":scheme","http"},
        /* 7  */ {":scheme","https"},
        /* 8  */ {":status","200"},
        /* 9  */ {":status","204"},
        /* 10 */ {":status","206"},
        /* 11 */ {":status","304"},
        /* 12 */ {":status","400"},
        /* 13 */ {":status","404"},
        /* 14 */ {":status","500"},
        /* 15 */ {"accept-charset",null},
        /* 16 */ {"accept-encoding","gzip, deflate"},
        /* 17 */ {"accept-language",null},
        /* 18 */ {"accept-ranges",null},
        /* 19 */ {"accept",null},
        /* 20 */ {"access-control-allow-origin",null},
        /* 21 */ {"age",null},
        /* 22 */ {"allow",null},
        /* 23 */ {"authorization",null},
        /* 24 */ {"cache-control",null},
        /* 25 */ {"content-disposition",null},
        /* 26 */ {"content-encoding",null},
        /* 27 */ {"content-language",null},
        /* 28 */ {"content-length",null},
        /* 29 */ {"content-location",null},
        /* 30 */ {"content-range",null},
        /* 31 */ {"content-type",null},
        /* 32 */ {"cookie",null},
        /* 33 */ {"date",null},
        /* 34 */ {"etag",null},
        /* 35 */ {"expect",null},
        /* 36 */ {"expires",null},
        /* 37 */ {"from",null},
        /* 38 */ {"host",null},
        /* 39 */ {"if-match",null},
        /* 40 */ {"if-modified-since",null},
        /* 41 */ {"if-none-match",null},
        /* 42 */ {"if-range",null},
        /* 43 */ {"if-unmodified-since",null},
        /* 44 */ {"last-modified",null},
        /* 45 */ {"link",null},
        /* 46 */ {"location",null},
        /* 47 */ {"max-forwards",null},
        /* 48 */ {"proxy-authenticate",null},
        /* 49 */ {"proxy-authorization",null},
        /* 50 */ {"range",null},
        /* 51 */ {"referer",null},
        /* 52 */ {"refresh",null},
        /* 53 */ {"retry-after",null},
        /* 54 */ {"server",null},
        /* 55 */ {"set-cookie",null},
        /* 56 */ {"strict-transport-security",null},
        /* 57 */ {"transfer-encoding",null},
        /* 58 */ {"user-agent",null},
        /* 59 */ {"vary",null},
        /* 60 */ {"via",null},
        /* 61 */ {"www-authenticate",null},
    };
    
    private static final Map<HttpField,Entry> __staticFieldMap = new HashMap<>();
    private static final Trie<Entry> __staticNameMap = new ArrayTernaryTrie<>(true,512);
    
    private static final StaticEntry[] __staticTable=new StaticEntry[STATIC_TABLE.length];
    static
    {
        Set<String> added = new HashSet<>();
        for (int i=1;i<STATIC_TABLE.length;i++)
        {
            StaticEntry entry;
            switch(i)
            {
                case 2:
                    entry=new StaticEntry(i,new StaticValueHttpField(STATIC_TABLE[i][0],STATIC_TABLE[i][1],HttpMethod.GET));
                    break;
                case 3:
                    entry=new StaticEntry(i,new StaticValueHttpField(STATIC_TABLE[i][0],STATIC_TABLE[i][1],HttpMethod.POST));
                    break;
                case 6:
                    entry=new StaticEntry(i,new StaticValueHttpField(STATIC_TABLE[i][0],STATIC_TABLE[i][1],HttpScheme.HTTP));
                    break;
                case 7:
                    entry=new StaticEntry(i,new StaticValueHttpField(STATIC_TABLE[i][0],STATIC_TABLE[i][1],HttpScheme.HTTPS));
                    break;
                case 8:
                case 11:
                    entry=new StaticEntry(i,new StaticValueHttpField(STATIC_TABLE[i][0],STATIC_TABLE[i][1],Integer.valueOf(STATIC_TABLE[i][1])));
                    break;
                    
                case 9:
                case 10:
                case 12:
                case 13:
                case 14:
                    entry=new StaticEntry(i,new StaticValueHttpField(STATIC_TABLE[i][0],STATIC_TABLE[i][1],Integer.valueOf(STATIC_TABLE[i][1])));
                    break;
                    
                default:
                    entry=new StaticEntry(i,new HttpField(STATIC_TABLE[i][0],STATIC_TABLE[i][1]));
            }
                        
            __staticTable[i]=entry;
            
            if (entry._field.getValue()!=null)
                __staticFieldMap.put(entry._field,entry);
            
            if (!added.contains(entry._field.getName()))
            {
                added.add(entry._field.getName());
                __staticNameMap.put(entry._field.getName(),entry);
                if (__staticNameMap.get(entry._field.getName())==null)
                    throw new IllegalStateException("name trie too small");
            }
        }
    }

    
    
    private int _maxHeaderTableSizeInBytes;
    private int _headerTableSizeInBytes;
    private final HeaderTable _headerTable;
    private final Map<HttpField,Entry> _fieldMap = new HashMap<>();
    private final Map<String,Entry> _nameMap = new HashMap<>();
    
    HpackContext(int maxHeaderTableSize)
    {
        _maxHeaderTableSizeInBytes=maxHeaderTableSize;
        int guesstimateEntries = 10+maxHeaderTableSize/(32+10+10);
        _headerTable=new HeaderTable(guesstimateEntries,guesstimateEntries+10);
        if (LOG.isDebugEnabled())
            LOG.debug(String.format("HdrTbl[%x] created max=%d",hashCode(),maxHeaderTableSize));
    }
    
    public void resize(int newMaxHeaderTableSize)
    {
        if (LOG.isDebugEnabled())
            LOG.debug(String.format("HdrTbl[%x] resized max=%d->%d",hashCode(),_maxHeaderTableSizeInBytes,newMaxHeaderTableSize));
        _maxHeaderTableSizeInBytes=newMaxHeaderTableSize;
        int guesstimateEntries = 10+newMaxHeaderTableSize/(32+10+10);
        evict();
        _headerTable.resizeUnsafe(guesstimateEntries);
    }
    
    public Entry get(HttpField field)
    {
        Entry entry = _fieldMap.get(field);
        if (entry==null)
            entry=__staticFieldMap.get(field);
        return entry;
    }
    
    public Entry get(String name)
    {
        Entry entry = __staticNameMap.get(name);
        if (entry!=null)
            return entry;
        return _nameMap.get(StringUtil.asciiToLowerCase(name));
    }
    
    public Entry get(int index)
    {
        if (index<__staticTable.length)
            return __staticTable[index];
            
        int d=_headerTable.size()-index+__staticTable.length-1;

        if (d>=0) 
            return _headerTable.getUnsafe(d);      
        return null;
    }
    
    public Entry add(HttpField field)
    {
        int slot=_headerTable.getNextSlotUnsafe();
        Entry entry=new Entry(slot,field);
        int size = entry.getSize();
        if (size>_maxHeaderTableSizeInBytes)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(String.format("HdrTbl[%x] !added size %d>%d",hashCode(),size,_maxHeaderTableSizeInBytes));
            return null;
        }
        _headerTableSizeInBytes+=size;
        _headerTable.addUnsafe(entry);
        _fieldMap.put(field,entry);
        _nameMap.put(StringUtil.asciiToLowerCase(field.getName()),entry);

        if (LOG.isDebugEnabled())
            LOG.debug(String.format("HdrTbl[%x] added %s",hashCode(),entry));
        evict();
        return entry;
    }

    /**
     * @return Current Header table size in entries
     */
    public int size()
    {
        return _headerTable.size();
    }
    
    /**
     * @return Current Header table size in Octets
     */
    public int getHeaderTableSize()
    {
        return _headerTableSizeInBytes;
    }

    /**
     * @return Max Header table size in Octets
     */
    public int getMaxHeaderTableSize()
    {
        return _maxHeaderTableSizeInBytes;
    }

    public int index(Entry entry)
    {
        if (entry._slot<0)
            return 0;
        if (entry.isStatic())
            return entry._slot;

        return _headerTable.index(entry)+__staticTable.length-1;
    }
    
    private void evict()
    {
        while (_headerTableSizeInBytes>_maxHeaderTableSizeInBytes)
        {
            Entry entry = _headerTable.pollUnsafe();
            if (LOG.isDebugEnabled())
                LOG.debug(String.format("HdrTbl[%x] evict %s",hashCode(),entry));
            _headerTableSizeInBytes-=entry.getSize();
            entry._slot=-1;
            _fieldMap.remove(entry.getHttpField());
            String lc=StringUtil.asciiToLowerCase(entry.getHttpField().getName());
            if (entry==_nameMap.get(lc))
                _nameMap.remove(lc);
        }
    }
    
    @Override
    public String toString()
    {
        return String.format("HpackContext@%x{%s}",hashCode(),_headerTable);
    }
    
    
    
    /* ------------------------------------------------------------ */
    /**
     */
    private class HeaderTable extends ArrayQueue<HpackContext.Entry>
    {
        /* ------------------------------------------------------------ */
        /**
         * @param initCapacity
         * @param growBy
         */
        private HeaderTable(int initCapacity, int growBy)
        {
            super(initCapacity,growBy);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.util.ArrayQueue#growUnsafe()
         */
        @Override
        protected void resizeUnsafe(int newCapacity)
        {
            // Relay on super.growUnsafe to pack all entries 0 to _nextSlot
            super.resizeUnsafe(newCapacity);
            for (int s=0;s<_nextSlot;s++)
                ((Entry)_elements[s])._slot=s;
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.util.ArrayQueue#enqueue(java.lang.Object)
         */
        @Override
        public boolean enqueue(Entry e)
        {
            return super.enqueue(e);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.util.ArrayQueue#dequeue()
         */
        @Override
        public Entry dequeue()
        {
            return super.dequeue();
        }
        
        /* ------------------------------------------------------------ */
        /**
         * @param entry
         * @return
         */
        private int index(Entry entry)
        {
            return entry._slot>=_nextE?_size-entry._slot+_nextE:_nextSlot-entry._slot;
        }

    }



    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static class Entry
    {
        final HttpField _field;
        int _slot;
        
        Entry()
        {    
            _slot=0;
            _field=null;
        }
        
        Entry(int index,String name, String value)
        {    
            _slot=index;
            _field=new HttpField(name,value);
        }
        
        Entry(int slot, HttpField field)
        {    
            _slot=slot;
            _field=field;
        }

        public int getSize()
        {
            return 32+_field.getName().length()+_field.getValue().length();
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
        
        public String toString()
        {
            return String.format("{%s,%d,%s,%x}",isStatic()?"S":"D",_slot,_field,hashCode());
        }
    } 
    
    public static class StaticEntry extends Entry
    {
        private final byte[] _huffmanValue;
        
        StaticEntry(int index,HttpField field)
        {    
            super(index,field);
            String value = field.getValue();
            if (value!=null && value.length()>0)
            {
                int huffmanLen = Huffman.octetsNeeded(value);
                int lenLen = NBitInteger.octectsNeeded(7,huffmanLen);
                _huffmanValue = new byte[1+lenLen+huffmanLen];
                ByteBuffer buffer = ByteBuffer.wrap(_huffmanValue); 
                        
                // Indicate Huffman
                buffer.put((byte)0x80);
                // Add huffman length
                NBitInteger.encode(buffer,7,huffmanLen);
                // Encode value
                Huffman.encode(buffer,value);       
            }
            else
                _huffmanValue=null;
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
    }


}
