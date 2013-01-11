//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Trie;


/* ------------------------------------------------------------ */
/** A HTTP Field
 */
public class HttpField
{
    public final static Trie<HttpField> CACHE = new ArrayTrie<>(768);
    public final static Trie<HttpField> CONTENT_TYPE = new ArrayTrie<>(512);
    
    static
    {
        CACHE.put(new CachedHttpField(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE));
        CACHE.put(new CachedHttpField(HttpHeader.CONNECTION,HttpHeaderValue.KEEP_ALIVE));
        CACHE.put(new CachedHttpField(HttpHeader.CONNECTION,HttpHeaderValue.UPGRADE));
        CACHE.put(new CachedHttpField(HttpHeader.ACCEPT_ENCODING,"gzip, deflate"));
        CACHE.put(new CachedHttpField(HttpHeader.ACCEPT_LANGUAGE,"en-US,en;q=0.5"));
        CACHE.put(new CachedHttpField(HttpHeader.ACCEPT,"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
        CACHE.put(new CachedHttpField(HttpHeader.PRAGMA,"no-cache"));
        CACHE.put(new CachedHttpField(HttpHeader.CACHE_CONTROL,"private, no-cache, no-cache=Set-Cookie, proxy-revalidate"));
        CACHE.put(new CachedHttpField(HttpHeader.CACHE_CONTROL,"no-cache"));
        CACHE.put(new CachedHttpField(HttpHeader.CONTENT_LENGTH,"0"));
        CACHE.put(new CachedHttpField(HttpHeader.CONTENT_ENCODING,"gzip"));
        CACHE.put(new CachedHttpField(HttpHeader.CONTENT_ENCODING,"deflate"));
        CACHE.put(new CachedHttpField(HttpHeader.EXPIRES,"Fri, 01 Jan 1990 00:00:00 GMT"));
        
        // TODO more user agents
        CACHE.put(new CachedHttpField(HttpHeader.USER_AGENT,"Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:17.0) Gecko/17.0 Firefox/17.0"));
        
        // Content types
        for (String type : new String[]{"text/plain","text/html","text/xml","text/json","application/x-www-form-urlencoded"})
        {
            HttpField field=new CachedHttpField(HttpHeader.CONTENT_TYPE,type);
            CACHE.put(field);
            CONTENT_TYPE.put(type,field);
            
            for (String charset : new String[]{"UTF-8","ISO-8859-1"})
            {
                String type_charset=type+"; charset="+charset;
                field=new CachedHttpField(HttpHeader.CONTENT_TYPE,type_charset);
                CACHE.put(field);
                CACHE.put(new CachedHttpField(HttpHeader.CONTENT_TYPE,type+";charset="+charset));
                CONTENT_TYPE.put(type_charset,field);
                CONTENT_TYPE.put(type+";charset="+charset,field);
            }
        }

        // Add headers with null values so HttpParser can avoid looking up name again for unknown values
        Set<HttpHeader> headers = new HashSet<>();
        for (String key:CACHE.keySet())
            headers.add(CACHE.get(key).getHeader());
        for (HttpHeader h:headers)
            if (!CACHE.put(new HttpField(h,(String)null)))
                throw new IllegalStateException("CACHE FULL");
    }

    private final static byte[] __colon_space = new byte[] {':',' '};
    
    private final HttpHeader _header;
    private final String _name;
    private final String _value;
        
    public HttpField(HttpHeader header, String name, String value)
    {
        _header = header;
        _name = name;
        _value = value;
    }  
    
    public HttpField(HttpHeader header, String value)
    {
        this(header,header.asString(),value);
    }
 
    
    public HttpField(HttpHeader header, HttpHeaderValue value)
    {
        this(header,header.asString(),value.asString());
    }
    
    public HttpField(String name, String value)
    {
        this(HttpHeader.CACHE.get(name),name,value);
    }

    public HttpHeader getHeader()
    {
        return _header;
    }

    public String getName()
    {
        return _name;
    }

    public String getValue()
    {
        return _value;
    }

    public boolean contains(String value)
    {
        if (_value==null)
            return false;

        if (value.equalsIgnoreCase(_value))
            return true;

        String[] split = _value.split("\\s*,\\s*");
        for (String s : split)
        {
            if (value.equalsIgnoreCase(s))
                return true;
        }

        return false;
    }


    public int getIntValue()
    {
        return StringUtil.toInt(_value);
    }

    public long getLongValue()
    {
        return StringUtil.toLong(_value);
    }
    
    private static byte[] toSanitisedName(String s)
    {
        byte[] bytes = s.getBytes(StringUtil.__ISO_8859_1_CHARSET);
        for (int i=bytes.length;i-->0;)
        {
            switch(bytes[i])
            {
                case '\r':
                case '\n':
                case ':' :
                    bytes[i]=(byte)'?';
            }
        }
        return bytes;
    }

    private static byte[] toSanitisedValue(String s)
    {
        byte[] bytes = s.getBytes(StringUtil.__ISO_8859_1_CHARSET);
        for (int i=bytes.length;i-->0;)
        {
            switch(bytes[i])
            {
                case '\r':
                case '\n':
                    bytes[i]=(byte)'?';
            }
        }
        return bytes;
    }

    public void putTo(ByteBuffer bufferInFillMode)
    {
        if (_header!=null)
        {
            bufferInFillMode.put(_header.getBytesColonSpace());
            bufferInFillMode.put(toSanitisedValue(_value));
        }
        else
        {
            bufferInFillMode.put(toSanitisedName(_name));
            bufferInFillMode.put(__colon_space);
            bufferInFillMode.put(toSanitisedValue(_value));
        }

        BufferUtil.putCRLF(bufferInFillMode);
    }

    public void putValueTo(ByteBuffer buffer)
    {
        buffer.put(toSanitisedValue(_value));
    }

    @Override
    public String toString()
    {
        String v=getValue();
        return getName() + ": " + (v==null?"":v.toString());
    }

    public boolean isSame(HttpField field)
    {
        if (field==null)
            return false;
        if (field==this)
            return true;
        if (_header!=null && _header==field.getHeader())
            return true;
        if (_name.equalsIgnoreCase(field.getName()))
            return true;
        return false;
    }
    
    
    /* ------------------------------------------------------------ */
    /** A HTTP Field optimised to be reused.
     */
    public static class CachedHttpField extends HttpField
    {
        final byte[] _bytes;
        public CachedHttpField(HttpHeader header, String value)
        {
            super(header,value);
            _bytes=new byte[header.asString().length()+2+value.length()+2];
            System.arraycopy(header.getBytesColonSpace(),0,_bytes,0,header.asString().length()+2);
            System.arraycopy(toSanitisedValue(value),0,_bytes,header.asString().length()+2,value.length());
            _bytes[_bytes.length-2]='\r';
            _bytes[_bytes.length-1]='\n';
        }

        CachedHttpField(HttpHeader header, HttpHeaderValue value)
        {
            this(header,value.asString());
        }
        
        @Override
        public void putTo(ByteBuffer bufferInFillMode)
        {
            bufferInFillMode.put(_bytes);
        }
    }
}
