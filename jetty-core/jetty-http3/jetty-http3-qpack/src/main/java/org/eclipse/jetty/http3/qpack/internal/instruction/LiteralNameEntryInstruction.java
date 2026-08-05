//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.List;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.compression.NBitStringEncoder;
import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

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
        _name = httpField.getLowerCaseName();
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
    public void encode(WritableBufferPool byteBufferPool, List<RetainableByteBuffer> accumulator)
    {
        int size = NBitStringEncoder.octetsNeeded(6, _name, _huffmanName) +
            NBitStringEncoder.octetsNeeded(8, _value, _huffmanValue);
        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(size, false);
        buffer.put((byte)0x40); // Instruction Pattern.
        NBitStringEncoder.encode(buffer, 6, _name, _huffmanName);
        NBitStringEncoder.encode(buffer, 8, _value, _huffmanValue);
        accumulator.add(buffer);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[name=%s,value=%s]", TypeUtil.toShortName(getClass()), hashCode(), getName(), getValue());
    }
}
