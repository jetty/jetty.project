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

import org.eclipse.jetty.http3.qpack.internal.util.HuffmanEncoder;
import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class IndexedNameEntryInstruction extends AbstractInstruction
{
    private final boolean _dynamic;
    private final int _index;
    private final boolean _huffman;
    private final String _value;

    public IndexedNameEntryInstruction(ByteBufferPool bufferPool, boolean dynamic, int index, boolean huffman, String value)
    {
        super(bufferPool);
        _dynamic = dynamic;
        _index = index;
        _huffman = huffman;
        _value = value;
    }

    public boolean isDynamic()
    {
        return _dynamic;
    }

    public int getIndex()
    {
        return _index;
    }

    public String getValue()
    {
        return _value;
    }

    @Override
    public void encode(ByteBufferPool.Accumulator accumulator)
    {
        int size = NBitIntegerEncoder.octetsNeeded(6, _index) + (_huffman ? HuffmanEncoder.octetsNeeded(_value) : _value.length()) + 2;
        RetainableByteBuffer buffer = getByteBufferPool().acquire(size, false);
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        BufferUtil.clearToFill(byteBuffer);

        // First bit indicates the instruction, second bit is whether it is a dynamic table reference or not.
        byteBuffer.put((byte)(0x80 | (_dynamic ? 0x00 : 0x40)));
        NBitIntegerEncoder.encode(byteBuffer, 6, _index);

        // We will not huffman encode the string.
        if (_huffman)
        {
            byteBuffer.put((byte)(0x80));
            NBitIntegerEncoder.encode(byteBuffer, 7, HuffmanEncoder.octetsNeeded(_value));
            HuffmanEncoder.encode(byteBuffer, _value);
        }
        else
        {
            byteBuffer.put((byte)(0x00));
            NBitIntegerEncoder.encode(byteBuffer, 7, _value.length());
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
