// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.io;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jetty.util.StringMap;
import org.eclipse.jetty.util.StringUtil;

/* ------------------------------------------------------------------------------- */
/** 
 * Stores a collection of {@link ByteBuffer} objects.
 * Buffers are stored in an ordered collection and can retreived by index or value
 * 
 */
public class BufferCache
{
    private final StringMap _stringMap=new StringMap(StringMap.CASE_INSENSTIVE);
    private final ArrayList<CachedBuffer> _index= new ArrayList<CachedBuffer>();
    
    /* ------------------------------------------------------------------------------- */
    /** Add a buffer to the cache at the specified index.
     * @param value The content of the buffer.
     */
    public CachedBuffer add(String value, int ordinal)
    {
        CachedBuffer buffer= new CachedBuffer(value, ordinal);
        _stringMap.put(value, buffer);
        if (ordinal>=0)
        {
            while ((ordinal - _index.size()) >= 0)
                _index.add(null);
            _index.set(ordinal,buffer);
        }
        return buffer;
    }

    public CachedBuffer get(int ordinal)
    {
        if (ordinal < 0 || ordinal >= _index.size())
            return null;
        return (CachedBuffer)_index.get(ordinal);
    }

    public ByteBuffer getBuffer(ByteBuffer buffer)
    {
        CachedBuffer cached=get(buffer);
        if (cached!=null)
            return cached.getBuffer();
        return buffer;
    }

    public String getString(ByteBuffer buffer)
    {
        CachedBuffer cached=get(buffer);
        if (cached!=null)
            return cached.toString();
        return BufferUtil.toString(buffer);
    }
    
    public CachedBuffer get(ByteBuffer buffer)
    {
        byte[] array=buffer.isReadOnly()?null:buffer.array();
        Map.Entry<String,Object> entry=_stringMap.getBestEntry(buffer);
        if (entry!=null)
            return (CachedBuffer)entry.getValue();
        return null;
    }

    public CachedBuffer get(String value)
    {
        return (CachedBuffer)_stringMap.get(value);
    }

    @Deprecated
    public ByteBuffer lookup(ByteBuffer buffer)
    {
        CachedBuffer cached=get(buffer);
        if (cached!=null)
            return cached.getBuffer();

        return buffer;
    }
    
    public CachedBuffer getBest(byte[] value, int offset, int maxLength)
    {
        Entry entry = _stringMap.getBestEntry(value, offset, maxLength);
        if (entry!=null)
            return (CachedBuffer)entry.getValue();
        return null;
    }

    public ByteBuffer lookup(String value)
    {
        CachedBuffer b= get(value);
        if (b == null)
            return ByteBuffer.wrap(value.getBytes(StringUtil.__ISO_8859_1_CHARSET));
        return b.getBuffer();
    }

    public String toString(ByteBuffer buffer)
    {
        CachedBuffer cached = get(buffer);
        if (cached!=null)
            return cached.toString();
        

        byte[] array=buffer.array();
        if (array!=null)
            return new String(array,buffer.position(),buffer.remaining(),StringUtil.__ISO_8859_1_CHARSET);
        
        array=new byte[buffer.remaining()];
        buffer.asReadOnlyBuffer().get(array);
        return new String(array,0,buffer.remaining(),StringUtil.__ISO_8859_1_CHARSET);
    }

    public int getOrdinal(String value)
    {
        CachedBuffer buffer = (CachedBuffer)_stringMap.get(value);
        return buffer==null?-1:buffer.getOrdinal();
    }
    
    public int getOrdinal(ByteBuffer buffer)
    {
        CachedBuffer cached = get(buffer);
        if (cached!=null)
            return cached.getOrdinal();
        return -1;
    }
    
    public static class CachedBuffer 
    {
        private final int _ordinal;
        private final String _string;
        private final ByteBuffer _buffer;
        private HashMap _associateMap=null;
        
        public CachedBuffer(String value, int ordinal)
        {
            _string=value;
            _buffer=ByteBuffer.wrap(value.getBytes(StringUtil.__ISO_8859_1_CHARSET)).asReadOnlyBuffer();
            _ordinal= ordinal;
        }

        public int getOrdinal()
        {
            return _ordinal;
        }

        public ByteBuffer getBuffer()
        {
            return _buffer;
        }

        public String toString()
        {
            return _string;
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
        	",stringMap="+_stringMap+
        	",index="+_index+
        	"]";
    }
}
