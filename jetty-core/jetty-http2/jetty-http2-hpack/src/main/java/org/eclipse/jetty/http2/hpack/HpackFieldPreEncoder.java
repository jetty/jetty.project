//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.hpack;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpFieldPreEncoder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.hpack.internal.Huffman;
import org.eclipse.jetty.http2.hpack.internal.NBitInteger;
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
            NBitInteger.encode(buffer, bits, nameIdx);
        else
        {
            buffer.put((byte)0x80);
            NBitInteger.encode(buffer, 7, Huffman.octetsNeededLC(name));
            Huffman.encodeLC(buffer, name);
        }

        HpackEncoder.encodeValue(buffer, huffman, value);

        BufferUtil.flipToFlush(buffer, 0);
        return BufferUtil.toArray(buffer);
    }
}
