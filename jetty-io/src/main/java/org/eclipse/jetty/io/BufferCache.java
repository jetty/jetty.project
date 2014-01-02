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

package org.eclipse.jetty.io;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;

import org.eclipse.jetty.util.StringMap;

/* ------------------------------------------------------------------------------- */
/** 
 * Stores a collection of {@link Buffer} objects.
 * Buffers are stored in an ordered collection and can retreived by index or value
 * 
 */
public class BufferCache
{
    private final HashMap _bufferMap=new HashMap();
    private final StringMap _stringMap=new StringMap(StringMap.CASE_INSENSTIVE);
    private final ArrayList _index= new ArrayList();

    /* ------------------------------------------------------------------------------- */
    /** Add a buffer to the cache at the specified index.
     * @param value The content of the buffer.
     */
    public CachedBuffer add(String value, int ordinal)
    {
        CachedBuffer buffer= new CachedBuffer(value, ordinal);
        _bufferMap.put(buffer, buffer);
        _stringMap.put(value, buffer);
        while ((ordinal - _index.size()) >= 0)
            _index.add(null);
        if (_index.get(ordinal)==null)
            _index.add(ordinal, buffer);
        return buffer;
    }

    public CachedBuffer get(int ordinal)
    {
        if (ordinal < 0 || ordinal >= _index.size())
            return null;
        return (CachedBuffer)_index.get(ordinal);
    }

    public CachedBuffer get(Buffer buffer)
    {
        return (CachedBuffer)_bufferMap.get(buffer);
    }

    public CachedBuffer get(String value)
    {
        return (CachedBuffer)_stringMap.get(value);
    }

    public Buffer lookup(Buffer buffer)
    {
        if (buffer instanceof CachedBuffer)
            return buffer;
        
        Buffer b= get(buffer);
        if (b == null)
        {
            if (buffer instanceof Buffer.CaseInsensitve)
                return buffer;
            return new ByteArrayBuffer.CaseInsensitive(buffer.asArray(),0,buffer.length(),Buffer.IMMUTABLE);
        }

        return b;
    }
    
    public CachedBuffer getBest(byte[] value, int offset, int maxLength)
    {
        Entry entry = _stringMap.getBestEntry(value, offset, maxLength);
        if (entry!=null)
            return (CachedBuffer)entry.getValue();
        return null;
    }

    public Buffer lookup(String value)
    {
        Buffer b= get(value);
        if (b == null)
        {
            return new CachedBuffer(value,-1);
        }
        return b;
    }

    public String toString(Buffer buffer)
    {
        return lookup(buffer).toString();
    }

    public int getOrdinal(String value)
    {
        CachedBuffer buffer = (CachedBuffer)_stringMap.get(value);
        return buffer==null?-1:buffer.getOrdinal();
    }
    
    public int getOrdinal(Buffer buffer)
    {
        if (buffer instanceof CachedBuffer)
            return ((CachedBuffer)buffer).getOrdinal();
        buffer=lookup(buffer);
        if (buffer!=null && buffer instanceof CachedBuffer)
            return ((CachedBuffer)buffer).getOrdinal();
        return -1;
    }
    
    public static class CachedBuffer extends ByteArrayBuffer.CaseInsensitive
    {
        private final int _ordinal;
        private HashMap _associateMap=null;
        
        public CachedBuffer(String value, int ordinal)
        {
            super(value);
            _ordinal= ordinal;
        }

        public int getOrdinal()
        {
            return _ordinal;
        }

        public CachedBuffer getAssociate(Object key)
        {
            if (_associateMap==null)
                return null;
            return (CachedBuffer)_associateMap.get(key);
        }

        // TODO Replace Associate with a mime encoding specific solution
        public void setAssociate(Object key, CachedBuffer associate)
        {
            if (_associateMap==null)
                _associateMap=new HashMap();
            _associateMap.put(key,associate);
        }
    }
    
    
    @Override
    public String toString()
    {
        return "CACHE["+
        	"bufferMap="+_bufferMap+
        	",stringMap="+_stringMap+
        	",index="+_index+
        	"]";
    }
}
