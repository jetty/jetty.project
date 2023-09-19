//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.http.compression.HuffmanEncoder;
import org.eclipse.jetty.http.compression.NBitIntegerEncoder;
import org.eclipse.jetty.util.BufferUtil;

/**
 *
 */
public class HpackFieldPreEncoder implements HttpFieldPreEncoder
{

    @Override
    public HttpVersion getHttpVersion()
    {
        return HttpVersion.HTTP_2;
    }

    @Override
    public byte[] getEncodedField(HttpHeader header, String name, String value)
    {
        boolean notIndexed = HpackEncoder.DO_NOT_INDEX.contains(header);

        ByteBuffer buffer = BufferUtil.allocate(name.length() + value.length() + 10);
        BufferUtil.clearToFill(buffer);
        boolean huffman;
        int bits;

        if (notIndexed)
        {
            // Non indexed field
            boolean neverIndex = HpackEncoder.NEVER_INDEX.contains(header);
            huffman = !HpackEncoder.DO_NOT_HUFFMAN.contains(header);
            buffer.put(neverIndex ? (byte)0x10 : (byte)0x00);
            bits = 4;
        }
        else if (header == HttpHeader.CONTENT_LENGTH && value.length() > 1)
        {
            // Non indexed content length for 2 digits or more
            buffer.put((byte)0x00);
            huffman = true;
            bits = 4;
        }
        else
        {
            // indexed
            buffer.put((byte)0x40);
            huffman = !HpackEncoder.DO_NOT_HUFFMAN.contains(header);
            bits = 6;
        }

        int nameIdx = HpackContext.staticIndex(header);
        if (nameIdx > 0)
            NBitIntegerEncoder.encode(buffer, bits, nameIdx);
        else
        {
            buffer.put((byte)0x80);
            NBitIntegerEncoder.encode(buffer, 7, HuffmanEncoder.octetsNeededLowerCase(name));
            HuffmanEncoder.encodeLowerCase(buffer, name);
        }

        HpackEncoder.encodeValue(buffer, huffman, value);

        BufferUtil.flipToFlush(buffer, 0);
        return BufferUtil.toArray(buffer);
    }
}
