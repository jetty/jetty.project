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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringMap;


/**
 * 
 */
public enum HttpHeaderValues 
{
    CLOSE("close"),
    CHUNKED("chunked"),
    GZIP("gzip"),
    IDENTITY("identity"),
    KEEP_ALIVE("keep-alive"),
    CONTINUE("100-continue"),
    PROCESSING("102-processing"),
    TE("TE"),
    BYTES("bytes"),
    NO_CACHE("no-cache"),
    UPGRADE("Upgrade");

    /* ------------------------------------------------------------ */
    public final static StringMap<HttpHeaderValues> CACHE= new StringMap<HttpHeaderValues>(true);
    static
    {
        for (HttpHeaderValues value : HttpHeaderValues.values())
            CACHE.put(value.toString(),value);
    }

    private final String _string;
    private final ByteBuffer _buffer;

    /* ------------------------------------------------------------ */
    HttpHeaderValues(String s)
    {
        _string=s;
        _buffer=BufferUtil.toBuffer(s);
    }

    /* ------------------------------------------------------------ */
    public ByteBuffer toBuffer()
    {
        return _buffer.asReadOnlyBuffer();
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return _string;
    }

    /* ------------------------------------------------------------ */
    private static EnumSet<HttpHeaders> __known = 
            EnumSet.of(HttpHeaders.CONNECTION,
                    HttpHeaders.TRANSFER_ENCODING,
                    HttpHeaders.CONTENT_ENCODING);
  
    /* ------------------------------------------------------------ */
    public static boolean hasKnownValues(HttpHeaders header)
    {
        if (header==null)
            return false;
        return __known.contains(header);
    }
}
