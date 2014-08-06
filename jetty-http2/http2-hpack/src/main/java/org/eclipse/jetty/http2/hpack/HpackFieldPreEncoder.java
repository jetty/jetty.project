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

import org.eclipse.jetty.http.HttpFieldPreEncoder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.hpack.HpackContext.Entry;
import org.eclipse.jetty.util.BufferUtil;


/* ------------------------------------------------------------ */
/**
 */
public class HpackFieldPreEncoder implements HttpFieldPreEncoder
{
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.HttpFieldPreEncoder#getHttpVersion()
     */
    @Override
    public HttpVersion getHttpVersion()
    {
        return HttpVersion.HTTP_2;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.HttpFieldPreEncoder#getEncodedField(org.eclipse.jetty.http.HttpHeader, java.lang.String, java.lang.String)
     */
    @Override
    public byte[] getEncodedField(HttpHeader header, String name, String value)
    {
        ByteBuffer buffer = BufferUtil.allocate(name.length()+value.length()+10);
        BufferUtil.clearToFill(buffer);
        buffer.put((byte)0x40);
        Entry entry = header==null?null:HpackContext.getStatic(header);
        if (entry==null)
        {
            buffer.put((byte)0x80);
            NBitInteger.encode(buffer,7,Huffman.octetsNeededLC(name));
            Huffman.encodeLC(buffer,name);
        }
        else
        {
            NBitInteger.encode(buffer,6,entry.getSlot());
        }

        buffer.put((byte)0x80);
        NBitInteger.encode(buffer,7,Huffman.octetsNeeded(value));
        Huffman.encode(buffer,value);
        BufferUtil.flipToFlush(buffer,0);
        return BufferUtil.toArray(buffer);
    }
}
