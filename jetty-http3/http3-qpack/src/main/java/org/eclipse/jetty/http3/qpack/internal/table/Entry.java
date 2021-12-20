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

package org.eclipse.jetty.http3.qpack.internal.table;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http3.qpack.internal.util.HuffmanEncoder;
import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerEncoder;
import org.eclipse.jetty.util.StringUtil;

public class Entry
{
    private final HttpField _field;
    private int _absoluteIndex;
    private final AtomicInteger _referenceCount = new AtomicInteger(0);

    public Entry()
    {
        this(-1, null);
    }

    public Entry(HttpField field)
    {
        this(-1, field);
    }

    public Entry(int index, HttpField field)
    {
        _field = field;
        _absoluteIndex = index;
    }

    public int getSize()
    {
        return getSize(_field);
    }

    public void setIndex(int index)
    {
        _absoluteIndex = index;
    }

    public int getIndex()
    {
        return _absoluteIndex;
    }

    public HttpField getHttpField()
    {
        return _field;
    }

    public void reference()
    {
        _referenceCount.incrementAndGet();
    }

    public void release()
    {
        _referenceCount.decrementAndGet();
    }

    public int getReferenceCount()
    {
        return _referenceCount.get();
    }

    public boolean isStatic()
    {
        return false;
    }

    public byte[] getStaticHuffmanValue()
    {
        return null;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{index=%d, refs=%d, field=\"%s\"}", getClass().getSimpleName(), hashCode(),
            _absoluteIndex, _referenceCount.get(), _field);
    }

    public static int getSize(Entry entry)
    {
        return getSize(entry.getHttpField());
    }

    public static int getSize(HttpField field)
    {
        return 32 + StringUtil.getLength(field.getName()) + StringUtil.getLength(field.getValue());
    }

    public static class StaticEntry extends Entry
    {
        private final byte[] _huffmanValue;
        private final byte _encodedField;

        StaticEntry(int index, HttpField field)
        {
            super(index, field);
            String value = field.getValue();
            if (value != null && value.length() > 0)
            {
                int huffmanLen = HuffmanEncoder.octetsNeeded(value);
                if (huffmanLen < 0)
                    throw new IllegalStateException("bad value");
                int lenLen = NBitIntegerEncoder.octectsNeeded(7, huffmanLen);
                _huffmanValue = new byte[1 + lenLen + huffmanLen];
                ByteBuffer buffer = ByteBuffer.wrap(_huffmanValue);

                // Indicate Huffman
                buffer.put((byte)0x80);
                // Add huffman length
                NBitIntegerEncoder.encode(buffer, 7, huffmanLen);
                // Encode value
                HuffmanEncoder.encode(buffer, value);
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
}
