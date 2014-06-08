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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.hpack.Field.NameKey;
import org.eclipse.jetty.http.HttpField;
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
            Entry entry=new Entry(i,STATIC_TABLE[i][0],STATIC_TABLE[i][1],true);
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
    
    private int _maxHeaderTableSize;
    private int _headerTableSize;
    private final Entry _refSet=new Entry(true);
    private final ArrayQueue<Entry> _headerTable;
    private final Map<HttpField,Entry> _fieldMap = new HashMap<>();
    private final Map<String,Entry> _nameMap = new HashMap<>();
    
    
    HpackContext(int maxHeaderTableSize)
    {
        _maxHeaderTableSize=maxHeaderTableSize;
        int guesstimateEntries = 10+maxHeaderTableSize/(32+10+10);
        _headerTable=new HeaderTable(guesstimateEntries,guesstimateEntries+10);
    }
    
    public Entry get(HttpField field)
    {
        System.err.println(field);
        System.err.println(_fieldMap);
        System.err.println(__staticFieldMap);
        Entry entry = _fieldMap.get(field);
        if (entry==null)
            entry=__staticFieldMap.get(field);
        return entry;
    }
    
    public Entry getNameEntry(String name)
    {
        Entry entry = __staticNameMap.get(name);
        if (entry!=null)
            return entry;
        return _nameMap.get(StringUtil.asciiToLowerCase(name));
    }
    
    public Entry add(HttpField field)
    {
        int i=_headerTable.getNextIndexUnsafe();
        Entry entry=new Entry(i,field,false);
        int size = entry.getSize();
        if (size>_maxHeaderTableSize)
            return null;
        _headerTableSize+=size;
        _headerTable.addUnsafe(entry);
        _fieldMap.put(field,entry);
        _nameMap.put(StringUtil.asciiToLowerCase(field.getName()),entry);

        evict();
        return entry;
    }
    
    public void evict()
    {
        while (_headerTableSize>_maxHeaderTableSize)
        {
            Entry entry = _headerTable.pollUnsafe();
            _headerTableSize-=entry.getSize();
            entry.removeFromRefSet();
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
        protected void growUnsafe(int newCapacity)
        {
            // Relay on super.growUnsafe to pack all entries 0 to _nextSlot
            super.growUnsafe(newCapacity);
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
    }



    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static class Entry
    {
        int _index;
        final boolean _static;
        final HttpField _field;
        Entry _refSetNext=this;
        Entry _refSetPrev=this;
        boolean _refSetUsed;
        
        Entry(boolean isStatic)
        {    
            _static=isStatic;
            _index=0;
            _field=null;
        }
        
        Entry(int index,String name, String value, boolean isStatic)
        {    
            _static=isStatic;
            _index=index;
            _field=new HttpField(name,value);
        }
        
        Entry(int index, HttpField field, boolean isStatic)
        {    
            _static=isStatic;
            _index=index;
            _field=field;
        }
        
        public void addToRefSet(HpackContext ctx)
        {
            _refSetNext=ctx._refSet;
            _refSetPrev=ctx._refSet._refSetPrev;
            ctx._refSet._refSetPrev._refSetNext=this;
            ctx._refSet._refSetPrev=this;
        }
        
        public void removeFromRefSet()
        {
            if (_refSetNext!=this)
            {
                _refSetNext._refSetPrev=_refSetPrev;
                _refSetPrev._refSetNext=_refSetNext;
                _refSetNext=this;
                _refSetPrev=this;
            }
        }

        public int getSize()
        {
            return 32+_field.getName().length()+_field.getValue().length();
        }
        
        /**
         * @return
         */
        public HttpField getHttpField()
        {
            return _field;
        }
        
        /**
         * @return
         */
        public boolean isStatic()
        {
            return _static;
        }
        
        public String toString()
        {
            return String.format("{%s,%d,%s,%x}",_static?"S":"D",_index,_field,hashCode());
        }
    }
    
    
    
    

}
