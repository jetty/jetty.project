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

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class BufferCallbackAccumulator
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
     * Get the amount of bytes which have been accumulated.
     * This will add up the remaining of each buffer in the accumulator.
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
        BufferUtil.clearToFill(buffer);
        writeTo(buffer);
        return bytes;
    }

    public void writeTo(ByteBuffer buffer)
    {
        for (Iterator<Entry> iterator = _entries.iterator(); iterator.hasNext();)
        {
            Entry entry = iterator.next();
            _length = entry.buffer.remaining();
            buffer.put(entry.buffer);
            iterator.remove();
            entry.callback.succeeded();
        }

        if (!_entries.isEmpty())
            throw new IllegalStateException("remaining entries: " + _entries.size());
        if (_length != 0)
            throw new IllegalStateException("non-zero length: " + _length);
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
