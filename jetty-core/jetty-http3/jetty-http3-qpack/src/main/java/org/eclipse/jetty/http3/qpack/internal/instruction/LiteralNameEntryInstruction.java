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
import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.http3.qpack.internal.util.HuffmanEncoder;
import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class LiteralNameEntryInstruction implements Instruction
{
    private final boolean _huffmanName;
    private final boolean _huffmanValue;
    private final String _name;
    private final String _value;

    public LiteralNameEntryInstruction(HttpField httpField, boolean huffman)
    {
        this(httpField, huffman, huffman);
    }

    public LiteralNameEntryInstruction(HttpField httpField, boolean huffmanName, boolean huffmanValue)
    {
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
    public void encode(ByteBufferPool.Lease lease)
    {
        int size = (_huffmanName ? HuffmanEncoder.octetsNeeded(_name) : _name.length()) +
            (_huffmanValue ? HuffmanEncoder.octetsNeeded(_value) : _value.length()) + 2;
        ByteBuffer buffer = lease.acquire(size, false);

        if (_huffmanName)
        {
            buffer.put((byte)(0x40 | 0x20));
            NBitIntegerEncoder.encode(buffer, 5, HuffmanEncoder.octetsNeeded(_name));
            HuffmanEncoder.encode(buffer, _name);
        }
        else
        {
            buffer.put((byte)(0x40));
            NBitIntegerEncoder.encode(buffer, 5, _name.length());
            buffer.put(_name.getBytes());
        }

        if (_huffmanValue)
        {
            buffer.put((byte)(0x80));
            NBitIntegerEncoder.encode(buffer, 7, HuffmanEncoder.octetsNeeded(_value));
            HuffmanEncoder.encode(buffer, _value);
        }
        else
        {
            buffer.put((byte)(0x00));
            NBitIntegerEncoder.encode(buffer, 5, _value.length());
            buffer.put(_value.getBytes());
        }

        BufferUtil.flipToFlush(buffer, 0);
        lease.append(buffer, true);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
