//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack.table;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http3.qpack.Huffman;
import org.eclipse.jetty.http3.qpack.NBitInteger;

public class StaticEntry extends Entry
{
    private final byte[] _huffmanValue;
    private final byte _encodedField;

    StaticEntry(int index, HttpField field)
    {
        super(index, field);
        String value = field.getValue();
        if (value != null && value.length() > 0)
        {
            int huffmanLen = Huffman.octetsNeeded(value);
            if (huffmanLen < 0)
                throw new IllegalStateException("bad value");
            int lenLen = NBitInteger.octectsNeeded(7, huffmanLen);
            _huffmanValue = new byte[1 + lenLen + huffmanLen];
            ByteBuffer buffer = ByteBuffer.wrap(_huffmanValue);

            // Indicate Huffman
            buffer.put((byte)0x80);
            // Add huffman length
            NBitInteger.encode(buffer, 7, huffmanLen);
            // Encode value
            Huffman.encode(buffer, value);
        }
        else
            _huffmanValue = null;

        _encodedField = (byte)(0x80 | index);
    }

    @Override
    public boolean isStatic()
    {
        return true;
    }

    @Override
    public byte[] getStaticHuffmanValue()
    {
        return _huffmanValue;
    }

    public byte getEncodedField()
    {
        return _encodedField;
    }
}
