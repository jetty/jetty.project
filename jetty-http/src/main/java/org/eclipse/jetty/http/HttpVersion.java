//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Trie;


/* ------------------------------------------------------------------------------- */
public enum HttpVersion
{
    HTTP_0_9("HTTP/0.9",9),
    HTTP_1_0("HTTP/1.0",10),
    HTTP_1_1("HTTP/1.1",11),
    HTTP_2("HTTP/2.0",20);

    /* ------------------------------------------------------------ */
    public final static Trie<HttpVersion> CACHE= new ArrayTrie<HttpVersion>();
    static
    {
        for (HttpVersion version : HttpVersion.values())
            CACHE.put(version.toString(),version);
    }

    /* ------------------------------------------------------------ */
    /** 
     * Optimised lookup to find a Http Version and whitespace in a byte array.
     * @param bytes Array containing ISO-8859-1 characters
     * @param position The first valid index
     * @param limit The first non valid index
     * @return A HttpMethod if a match or null if no easy match.
     */
    public static HttpVersion lookAheadGet(byte[] bytes, int position, int limit)
    {
        int length=limit-position;
        if (length<9)
            return null;

        if (bytes[position+4]=='/' && bytes[position+6]=='.' && Character.isWhitespace((char)bytes[position+8]) &&
            ((bytes[position]=='H' &&  bytes[position+1]=='T' && bytes[position+2]=='T' && bytes[position+3]=='P') ||
             (bytes[position]=='h' &&  bytes[position+1]=='t' && bytes[position+2]=='t' && bytes[position+3]=='p')))
        {
            switch(bytes[position+5])
            {
                case '1':
                    switch(bytes[position+7])
                    {
                        case '0':
                            return HTTP_1_0;
                        case '1':
                            return HTTP_1_1;
                    }
                    break;
                case '2':
                    switch(bytes[position+7])
                    {
                        case '0':
                            return HTTP_2;
                    }
                    break;
            }
        }
        
        return null;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Optimised lookup to find a HTTP Version and trailing white space in a byte array.
     * @param buffer buffer containing ISO-8859-1 characters
     * @return A HttpVersion if a match or null if no easy match.
     */
    public static HttpVersion lookAheadGet(ByteBuffer buffer)
    {
        if (buffer.hasArray())
            return lookAheadGet(buffer.array(),buffer.arrayOffset()+buffer.position(),buffer.arrayOffset()+buffer.limit());
        return null;
    }
    
    
    private final String _string;
    private final byte[] _bytes;
    private final ByteBuffer _buffer;
    private final int _version;

    /* ------------------------------------------------------------ */
    HttpVersion(String s,int version)
    {
        _string=s;
        _bytes=StringUtil.getBytes(s);
        _buffer=ByteBuffer.wrap(_bytes);
        _version=version;
    }

    /* ------------------------------------------------------------ */
    public byte[] toBytes()
    {
        return _bytes;
    }

    /* ------------------------------------------------------------ */
    public ByteBuffer toBuffer()
    {
        return _buffer.asReadOnlyBuffer();
    }

    /* ------------------------------------------------------------ */
    public int getVersion()
    {
        return _version;
    }

    /* ------------------------------------------------------------ */
    public boolean is(String s)
    {
        return _string.equalsIgnoreCase(s);    
    }
    
    /* ------------------------------------------------------------ */
    public String asString()
    {
        return _string;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return _string;
    }

    /**
     * Case insensitive fromString() conversion
     * @param version the String to convert to enum constant
     * @return the enum constant or null if version unknown
     */
    public static HttpVersion fromString(String version)
    {
        return CACHE.get(version);
    }

    /* ------------------------------------------------------------ */
    public static HttpVersion fromVersion(int version)
    {
        switch(version)
        {
            case 9: return HttpVersion.HTTP_0_9;
            case 10: return HttpVersion.HTTP_1_0;
            case 11: return HttpVersion.HTTP_1_1;
            case 20: return HttpVersion.HTTP_2;
            default: throw new IllegalArgumentException();
        }
    }
}
