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

package org.eclipse.jetty.hpack;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Trie;

public class HpackContext
{
    private static final String[][] STATIC_TABLE = 
    {
        {null,null},
        /* 1  */ {":authority"                  ,null},
        /* 2  */ {":method"                     ,"GET"},
        /* 3  */ {":method"                     ,"POST"},
        /* 4  */ {":path"                       ,"/"},
        /* 5  */ {":path"                       ,"/index.html"},
        /* 6  */ {":scheme"                     ,"http"},
        /* 7  */ {":scheme"                     ,"https"},
        /* 8  */ {":status"                     ,"200"},
        /* 9  */ {":status"                     ,"204"},
        /* 10 */ {":status"                     ,"206"},
        /* 11 */ {":status"                     ,"304"},
        /* 12 */ {":status"                     ,"400"},
        /* 13 */ {":status"                     ,"404"},
        /* 14 */ {":status"                     ,"500"},
        /* 15 */ {"accept-charset"              ,null},
        /* 16 */ {"accept-encoding"             ,null},
        /* 17 */ {"accept-language"             ,null},
        /* 18 */ {"accept-ranges"               ,null},
        /* 19 */ {"accept"                      ,null},
        /* 20 */ {"access-control-allow-origin" ,null},
        /* 21 */ {"age"                         ,null},
        /* 22 */ {"allow"                       ,null},
        /* 23 */ {"authorization"               ,null},
        /* 24 */ {"cache-control"               ,null},
        /* 25 */ {"content-disposition"         ,null},
        /* 26 */ {"content-encoding"            ,null},
        /* 27 */ {"content-language"            ,null},
        /* 28 */ {"content-length"              ,null},
        /* 29 */ {"content-location"            ,null},
        /* 30 */ {"content-range"               ,null},
        /* 31 */ {"content-type"                ,null},
        /* 32 */ {"cookie"                      ,null},
        /* 33 */ {"date"                        ,null},
        /* 34 */ {"etag"                        ,null},
        /* 35 */ {"expect"                      ,null},
        /* 36 */ {"expires"                     ,null},
        /* 37 */ {"from"                        ,null},
        /* 38 */ {"host"                        ,null},
        /* 39 */ {"if-match"                    ,null},
        /* 40 */ {"if-modified-since"           ,null},
        /* 41 */ {"if-none-match"               ,null},
        /* 42 */ {"if-range"                    ,null},
        /* 43 */ {"if-unmodified-since"         ,null},
        /* 44 */ {"last-modified"               ,null},
        /* 45 */ {"link"                        ,null},
        /* 46 */ {"location"                    ,null},
        /* 47 */ {"max-forwards"                ,null},
        /* 48 */ {"proxy-authenticate"          ,null},
        /* 49 */ {"proxy-authorization"         ,null},
        /* 50 */ {"range"                       ,null},
        /* 51 */ {"referer"                     ,null},
        /* 52 */ {"refresh"                     ,null},
        /* 53 */ {"retry-after"                 ,null},
        /* 54 */ {"server"                      ,null},
        /* 55 */ {"set-cookie"                  ,null},
        /* 56 */ {"strict-transport-security"   ,null},
        /* 57 */ {"transfer-encoding"           ,null},
        /* 58 */ {"user-agent"                  ,null},
        /* 59 */ {"vary"                        ,null},
        /* 60 */ {"via"                         ,null},
        /* 61 */ {"www-authenticate"            ,null},
    
    };
    
    private static final Map<HttpField,Entry> __staticFieldMap = new HashMap<>();
    private static final Trie<Entry> __staticNameMap = new ArrayTrie<>(24);

    private static final Entry[] __staticTable=new Entry[STATIC_TABLE.length];
    static
    {
        Set<String> added = new HashSet<>();
        for (int i=1;i<STATIC_TABLE.length;i++)
        {
            Entry entry;
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
                case 9:
                case 10:
                case 11:
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
            }
        }
    }
    
    private int _maxHeaderTableSizeInBytes;
    private int _headerTableSizeInBytes;
    private final Entry _refSet=new Entry();
    private final HeaderTable _headerTable;
    private final Map<HttpField,Entry> _fieldMap = new HashMap<>();
    private final Map<String,Entry> _nameMap = new HashMap<>();
    private Iterable<Entry> referenceSet = new Iterable<Entry>()
    {
        @Override
        public Iterator<Entry> iterator()
        {
            return iterateReferenceSet();
        }
    };
    
    
    HpackContext(int maxHeaderTableSize)
    {
        _maxHeaderTableSizeInBytes=maxHeaderTableSize;
        int guesstimateEntries = 10+maxHeaderTableSize/(32+10+10);
        _headerTable=new HeaderTable(guesstimateEntries,guesstimateEntries+10);
    }
    
    public void resize(int maxHeaderTableSize)
    {
        _maxHeaderTableSizeInBytes=maxHeaderTableSize;
        int guesstimateEntries = 10+maxHeaderTableSize/(32+10+10);
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
        index=index-_headerTable.size();
        if (index>0)
        {
            if (index>=__staticTable.length)
                return null;
            return __staticTable[index];
        }
        
        return _headerTable.getUnsafe(-index);
    }
    
    public Entry add(HttpField field)
    {
        int i=_headerTable.getNextIndexUnsafe();
        Entry entry=new Entry(i,field);
        int size = entry.getSize();
        if (size>_maxHeaderTableSizeInBytes)
            return null;
        _headerTableSizeInBytes+=size;
        _headerTable.addUnsafe(entry);
        _fieldMap.put(field,entry);
        _nameMap.put(StringUtil.asciiToLowerCase(field.getName()),entry);

        evict();
        return entry;
    }

    public Object size()
    {
        return _headerTable.size();
    }
    
    public int index(Entry entry)
    {
        if (entry._index<0)
            return 0;
        if (entry.isStatic())
            return _headerTable.size() + entry._index;
        return _headerTable.index(entry);
    }
    
    public void addToRefSet(Entry entry)
    {
        entry.addToRefSet(this);
    }
    
    public Iterable<Entry> getReferenceSet()
    {
        return referenceSet;
    }
    
    public void clearReferenceSet()
    {
        Entry entry = _refSet._refSetNext;
        while(entry!=_refSet)
        {
            Entry next = entry._refSetNext;
            entry.removeFromRefSet();
            entry=next;
        }
    }
    

    public void removedUnusedReferences(ByteBuffer buffer)
    {
        Entry entry = _refSet._refSetNext;
        while(entry!=_refSet)
        {
            Entry next = entry._refSetNext;
            
            if (entry.isUsed())
                entry._used=false;
            else
            {
                // encode the reference to remove it
                buffer.put((byte)0x80);
                NBitInteger.encode(buffer,7,index(entry));
                entry.removeFromRefSet();
            }
            entry=next;
        }
    }

    public void emitUnusedReferences(MetaDataBuilder builder)
    {
        Entry entry = _refSet._refSetNext;
        while(entry!=_refSet)
        {
            if (entry.isUsed())
                entry._used=false;
            else
                builder.emit(entry.getHttpField());
            
            entry=entry._refSetNext;
        }
    }

    
    public Iterator<Entry> iterateReferenceSet()
    {
        return new Iterator<Entry>()
        {
            Entry _next = _refSet._refSetNext;

            @Override
            public boolean hasNext()
            {
                return _next!=_refSet;
            }

            @Override
            public Entry next()
            {
                if (_next==_refSet)
                    throw new NoSuchElementException();
                Entry next=_next;
                _next=_next._refSetNext;
                return next;
            }

            @Override
            public void remove()
            {
                if (_next._refSetPrev==_refSet)
                    throw new NoSuchElementException();
                    
                _next._refSetPrev.removeFromRefSet();
            }
        };
    }
    
    private void evict()
    {
        while (_headerTableSizeInBytes>_maxHeaderTableSizeInBytes)
        {
            Entry entry = _headerTable.pollUnsafe();
            _headerTableSizeInBytes-=entry.getSize();
            entry.removeFromRefSet();
            entry._index=-1;
            _fieldMap.remove(entry.getHttpField());
            String lc=StringUtil.asciiToLowerCase(entry.getHttpField().getName());
            if (entry==_nameMap.get(lc))
                _nameMap.remove(lc);
        }
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
            for (int i=0;i<_nextSlot;i++)
                ((Entry)_elements[i])._index=i;
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
            return entry._index>=_nextE?_size-entry._index+_nextE:_nextSlot-entry._index;
        }

    }



    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static class Entry
    {
        final HttpField _field;
        int _index;
        Entry _refSetNext=this;
        Entry _refSetPrev=this;
        boolean _used;
        
        Entry()
        {    
            _index=0;
            _field=null;
        }
        
        Entry(int index,String name, String value)
        {    
            _index=index;
            _field=new HttpField(name,value);
        }
        
        Entry(int index, HttpField field)
        {    
            _index=index;
            _field=field;
        }
        
        private void addToRefSet(HpackContext ctx)
        {
            if (isStatic())
                throw new IllegalStateException("static");
            if (_index<0)
                throw new IllegalStateException("evicted");
            if (_refSetNext!=this)
                return;

            _used=true;
            _refSetNext=ctx._refSet;
            _refSetPrev=ctx._refSet._refSetPrev;
            ctx._refSet._refSetPrev._refSetNext=this;
            ctx._refSet._refSetPrev=this;
        }

        public boolean isInReferenceSet()
        {
            return _refSetNext!=this;
        }
        
        public void removeFromRefSet()
        {
            if (_refSetNext!=this)
            {
                _refSetNext._refSetPrev=_refSetPrev;
                _refSetPrev._refSetNext=_refSetNext;
                _refSetNext=this;
                _refSetPrev=this;
                _used=false;
            }
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
            return String.format("{%s,%d,%s,%x}",isStatic()?"S":"D",_index,_field,hashCode());
        }

        public void used()
        {
            _used=true;
        }

        public boolean isUsed()
        {
            return _used;
        }
    } 
    
    public static class StaticEntry extends Entry
    {
        final byte[] _huffmanValue;
        
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

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static class StaticValueHttpField extends HttpField
    {
        private final Object _value;

        public StaticValueHttpField(String name, String valueString, Object value)
        {
            super(name,valueString);
            _value=value;
        }

        public Object getStaticValue()
        {
            return _value;
        }
    }

}
