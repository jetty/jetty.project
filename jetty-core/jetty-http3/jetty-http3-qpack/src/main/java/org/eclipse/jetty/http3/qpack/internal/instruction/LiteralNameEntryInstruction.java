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

package org.eclipse.jetty.http3.qpack.internal.instruction;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http3.qpack.internal.util.HuffmanEncoder;
import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class LiteralNameEntryInstruction extends AbstractInstruction
{
    private final boolean _huffmanName;
    private final boolean _huffmanValue;
    private final String _name;
    private final String _value;

    public LiteralNameEntryInstruction(ByteBufferPool bufferPool, HttpField httpField, boolean huffman)
    {
        this(bufferPool, httpField, huffman, huffman);
    }

    public LiteralNameEntryInstruction(ByteBufferPool bufferPool, HttpField httpField, boolean huffmanName, boolean huffmanValue)
    {
        super(bufferPool);
        _huffmanName = huffmanName;
        _huffmanValue = huffmanValue;
        _name = httpField.getName();
        _value = httpField.getValue();
    }

    public String getName()
    {
        return _name;
    }

    public String getValue()
    {
        return _value;
    }

    @Override
    public void encode(ByteBufferPool.Accumulator accumulator)
    {
        int size = (_huffmanName ? HuffmanEncoder.octetsNeeded(_name) : _name.length()) +
            (_huffmanValue ? HuffmanEncoder.octetsNeeded(_value) : _value.length()) + 2;
        RetainableByteBuffer buffer = getByteBufferPool().acquire(size, false);
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        BufferUtil.clearToFill(byteBuffer);

        if (_huffmanName)
        {
            byteBuffer.put((byte)(0x40 | 0x20));
            NBitIntegerEncoder.encode(byteBuffer, 5, HuffmanEncoder.octetsNeeded(_name));
            HuffmanEncoder.encode(byteBuffer, _name);
        }
        else
        {
            byteBuffer.put((byte)(0x40));
            NBitIntegerEncoder.encode(byteBuffer, 5, _name.length());
            byteBuffer.put(_name.getBytes());
        }

        if (_huffmanValue)
        {
            byteBuffer.put((byte)(0x80));
            NBitIntegerEncoder.encode(byteBuffer, 7, HuffmanEncoder.octetsNeeded(_value));
            HuffmanEncoder.encode(byteBuffer, _value);
        }
        else
        {
            byteBuffer.put((byte)(0x00));
            NBitIntegerEncoder.encode(byteBuffer, 5, _value.length());
            byteBuffer.put(_value.getBytes());
        }

        BufferUtil.flipToFlush(byteBuffer, 0);
        accumulator.append(buffer);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
