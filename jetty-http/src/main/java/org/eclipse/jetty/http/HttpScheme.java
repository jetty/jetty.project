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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringMap;

/* ------------------------------------------------------------------------------- */
/** 
 */
public enum HttpScheme
{    
    HTTP("http"),
    HTTPS("https"),
    WS("ws"),
    WSS("wss");

    /* ------------------------------------------------------------ */
    public final static StringMap<HttpScheme> CACHE= new StringMap<HttpScheme>(true);
    static
    {
        for (HttpScheme version : HttpScheme.values())
            CACHE.put(version.toString(),version);
    }

    private final String _string;
    private final ByteBuffer _buffer;

    /* ------------------------------------------------------------ */
    HttpScheme(String s)
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
}
