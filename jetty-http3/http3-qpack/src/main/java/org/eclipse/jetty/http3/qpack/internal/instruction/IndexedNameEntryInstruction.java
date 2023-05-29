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

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.compression.NBitIntegerEncoder;
import org.eclipse.jetty.http.compression.NBitStringEncoder;
import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class IndexedNameEntryInstruction implements Instruction
{
    private final boolean _dynamic;
    private final int _index;
    private final boolean _huffman;
    private final String _value;

    public IndexedNameEntryInstruction(boolean dynamic, int index, boolean huffman, String value)
    {
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
    public void encode(ByteBufferPool.Lease lease)
    {
        int size = NBitIntegerEncoder.octetsNeeded(6, _index) + NBitStringEncoder.octetsNeeded(8, _value, _huffman);
        ByteBuffer buffer = lease.acquire(size, false);

        // First bit indicates the instruction, second bit is whether it is a dynamic table reference or not.
        buffer.put((byte)(0x80 | (_dynamic ? 0x00 : 0x40)));
        NBitIntegerEncoder.encode(buffer, 6, _index);

        NBitStringEncoder.encode(buffer, 8, _value, _huffman);
        BufferUtil.flipToFlush(buffer, 0);
        lease.append(buffer, true);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[index=%d,name=%s]", getClass().getSimpleName(), hashCode(), getIndex(), getValue());
    }
}
