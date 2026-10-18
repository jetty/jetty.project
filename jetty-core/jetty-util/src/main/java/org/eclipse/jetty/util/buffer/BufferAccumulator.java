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

package org.eclipse.jetty.util.buffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Accumulates [RetainableByteBuffer]s that can be later merged into a
/// single buffer for a final operation such as writing it to the network.
public class BufferAccumulator
{
    private final List<RetainableByteBuffer> buffers = new ArrayList<>();

    /// Retains and adds the given buffer to this accumulator.
    ///
    /// A buffer must not be added more than once; to accumulate
    /// the same bytes multiple times, add different slices of the buffer.
    ///
    /// @param buffer the buffer to add
    /// @throws IllegalArgumentException if the buffer was already added, in which case it is not retained
    public void add(RetainableByteBuffer buffer)
    {
        addRetained(buffer);
        buffer.retain();
    }

    /// Adds the given buffer to this accumulator.
    ///
    /// Differently from [#add(RetainableByteBuffer)], this method does not
    /// retain the given buffer, which therefore must be already retained
    /// by the calling code.
    ///
    /// A buffer must not be added more than once; to accumulate
    /// the same bytes multiple times, add different slices of the buffer.
    ///
    /// @param buffer the buffer to add
    /// @throws IllegalArgumentException if the buffer was already added, in
    /// which case the calling code remains responsible for releasing the buffer
    public void addRetained(RetainableByteBuffer buffer)
    {
        Objects.requireNonNull(buffer);
        for (RetainableByteBuffer existing : buffers)
        {
            if (existing == buffer && buffer.hasRemaining())
                throw new IllegalArgumentException("duplicate non-empty buffer " + buffer);
        }
        buffers.add(buffer);
    }

    /// @return whether this accumulator has bytes that can be read
    public boolean hasRemaining()
    {
        for (RetainableByteBuffer buffer : buffers)
        {
            if (buffer.hasRemaining())
                return true;
        }
        return false;
    }

    /// @return the number of bytes that can be read
    public long remaining()
    {
        long remaining = 0;
        for (RetainableByteBuffer buffer : buffers)
        {
            remaining += buffer.remaining();
        }
        return remaining;
    }

    /// Returns the accumulated buffers, leaving this accumulator empty.
    ///
    /// The returned buffer must be released.
    ///
    /// @return the accumulated buffers
    public RetainableByteBuffer take()
    {
        if (buffers.isEmpty())
            return RetainableByteBuffer.empty();
        RetainableByteBuffer buffer;
        if (buffers.size() == 1)
        {
            buffer = buffers.getFirst();
            buffers.clear();
        }
        else
        {
            buffer = RetainableByteBuffer.merge(buffers);
            clear();
        }
        return buffer;
    }

    /// Clears this accumulator, releasing any accumulated buffer.
    public void clear()
    {
        buffers.forEach(RetainableByteBuffer::release);
        buffers.clear();
    }
}
