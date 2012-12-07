//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Trie;


/* ------------------------------------------------------------ */
/** A HTTP Field
 */
public class HttpField
{
    public final static Trie<HttpField> CACHE = new Trie<>();
    
    static
    {
        CACHE.put(new HttpField(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE));
        CACHE.put(new HttpField(HttpHeader.CONNECTION,HttpHeaderValue.KEEP_ALIVE));
        CACHE.put(new HttpField(HttpHeader.ACCEPT_ENCODING,"gzip, deflate"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT_LANGUAGE,"en-US,en;q=0.5"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT,"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
        CACHE.put(new HttpField(HttpHeader.PRAGMA,"no-cache"));
        CACHE.put(new HttpField(HttpHeader.CACHE_CONTROL,"private, no-cache, no-cache=Set-Cookie, proxy-revalidate"));
        CACHE.put(new HttpField(HttpHeader.CACHE_CONTROL,"no-cache"));
        CACHE.put(new HttpField(HttpHeader.CONTENT_LENGTH,"0"));
        CACHE.put(new HttpField(HttpHeader.CONTENT_TYPE,"text/plain; charset=utf-8"));
        CACHE.put(new HttpField(HttpHeader.CONTENT_TYPE,"application/x-www-form-urlencoded; charset=UTF-8"));
        CACHE.put(new HttpField(HttpHeader.CONTENT_ENCODING,"gzip"));
        CACHE.put(new HttpField(HttpHeader.CONTENT_ENCODING,"deflate"));
        CACHE.put(new HttpField(HttpHeader.EXPIRES,"Fri, 01 Jan 1990 00:00:00 GMT"));
        
        
        // Add headers with null values so HttpParser can avoid looking up name again for unknown values
        CACHE.put(new HttpField(HttpHeader.CONNECTION,(String)null));
        CACHE.put(new HttpField(HttpHeader.ACCEPT_ENCODING,(String)null));
        CACHE.put(new HttpField(HttpHeader.ACCEPT_LANGUAGE,(String)null));
        CACHE.put(new HttpField(HttpHeader.ACCEPT,(String)null));
        CACHE.put(new HttpField(HttpHeader.PRAGMA,(String)null));
        CACHE.put(new HttpField(HttpHeader.CONTENT_TYPE,(String)null));
        CACHE.put(new HttpField(HttpHeader.CONTENT_LENGTH,(String)null));
        CACHE.put(new HttpField(HttpHeader.CONTENT_ENCODING,(String)null));
        CACHE.put(new HttpField(HttpHeader.EXPIRES,(String)null));
        
        // TODO add common user agents
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
        _header = header;
        _name = _header.asString();
        _value = value;
    }
 
    
    public HttpField(HttpHeader header, HttpHeaderValue value)
    {
        _header = header;
        _name = _header.asString();
        _value = value.asString();
    }
    
    public HttpField(String name, String value)
    {
        _header = HttpHeader.CACHE.get(name);
        _name = name;
        _value = value;
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
    
    private byte[] toSanitisedName(String s)
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

    private byte[] toSanitisedValue(String s)
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
        HttpHeader header = HttpHeader.CACHE.get(_name);
        if (header!=null)
        {
            bufferInFillMode.put(header.getBytesColonSpace());

            if (HttpHeaderValue.hasKnownValues(header))
            {
                HttpHeaderValue value=HttpHeaderValue.CACHE.get(_value);
                if (value!=null)
                    bufferInFillMode.put(value.toBuffer());
                else
                    bufferInFillMode.put(toSanitisedValue(_value));
            }
            else
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
}
