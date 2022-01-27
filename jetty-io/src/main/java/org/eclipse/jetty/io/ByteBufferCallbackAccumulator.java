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

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

/**
 * This class can be used to accumulate pairs of {@link ByteBuffer} and {@link Callback}, and eventually copy
 * these into a single {@link ByteBuffer} or byte array and succeed the callbacks.
 */
public class ByteBufferCallbackAccumulator
{
    private final List<Entry> _entries = new ArrayList<>();
    private int _length;

    private static class Entry
    {
        private final ByteBuffer buffer;
        private final Callback callback;

        Entry(ByteBuffer buffer, Callback callback)
        {
            this.buffer = buffer;
            this.callback = callback;
        }
    }

    public void addEntry(ByteBuffer buffer, Callback callback)
    {
        _entries.add(new Entry(buffer, callback));
        _length = Math.addExact(_length, buffer.remaining());
    }

    /**
     * @return the total length of the content in the accumulator.
     */
    public int getLength()
    {
        return _length;
    }

    /**
     * @return a newly allocated byte array containing all content written into the accumulator.
     */
    public byte[] takeByteArray()
    {
        int length = getLength();
        if (length == 0)
            return new byte[0];

        byte[] bytes = new byte[length];
        ByteBuffer buffer = BufferUtil.toBuffer(bytes);
        BufferUtil.clear(buffer);
        writeTo(buffer);
        return bytes;
    }

    public void writeTo(ByteBuffer buffer)
    {
        if (BufferUtil.space(buffer) < _length)
            throw new IllegalArgumentException("not enough buffer space remaining");

        int pos = BufferUtil.flipToFill(buffer);
        for (Entry entry : _entries)
        {
            buffer.put(entry.buffer);
            entry.callback.succeeded();
        }
        BufferUtil.flipToFlush(buffer, pos);
        _entries.clear();
        _length = 0;
    }

    public void fail(Throwable t)
    {
        for (Entry entry : _entries)
        {
            entry.callback.failed(t);
        }
        _entries.clear();
        _length = 0;
    }
}
