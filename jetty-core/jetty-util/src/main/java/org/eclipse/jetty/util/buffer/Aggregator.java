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

import java.nio.BufferOverflowException;
import java.nio.charset.Charset;

import org.eclipse.jetty.util.Retainable;

/// Copies [RetainableByteBuffer]s by aggregating them into a single [RetainableByteBuffer]
/// backing store, growing the backing store if configured so and if necessary.
///
/// The aggregated bytes can either be _read_ or _taken_.
///
/// The _read_ operations copy the aggregated bytes and leave the aggregator unmodified.
///
/// The _take_ operations return a [RetainableByteBuffer] that callers must release.
/// The returned [RetainableByteBuffer] contains all the bytes of this aggregator,
/// and the internal backing store of this aggregator becomes empty.
public class Aggregator implements AutoCloseable
{
    private RetainableByteBuffer.Mutable buffer;
    private final boolean growable;

    public Aggregator(boolean growable, int initialCapacity)
    {
        this.growable = growable;
        this.buffer = RetainableByteBuffer.Mutable.allocate(initialCapacity, true);
    }

    /// @return how many bytes are present in this aggregator
    public long remaining()
    {
        return buffer.remaining();
    }

    /// Appends the given [RetainableByteBuffer] to this aggregator,
    /// growing the backing store if configured so and if necessary.
    ///
    /// @return the number of bytes appended
    public long append(RetainableByteBuffer data)
    {
        long remaining = data.remaining();
        if (buffer.space() < remaining)
            grow(remaining);
        return buffer.append(data);
    }

    /// Returns the contents of this aggregator as a [String] using the given [Charset].
    ///
    /// This aggregator is left unmodified.
    ///
    /// @return the contents of this aggregator as a [String]
    public String getString(Charset charset)
    {
        return buffer.getString(buffer.readPosition(), charset);
    }

    /// Returns the contents of this aggregator as a [RetainableByteBuffer] that must be released.
    ///
    /// This aggregator is modified, and the internal backing store is empty.
    ///
    ///
    public RetainableByteBuffer take()
    {
        RetainableByteBuffer.Mutable result = buffer;
        buffer = RetainableByteBuffer.Mutable.allocate((int)result.capacity(), true);
        return result;
    }

    /// Empties the internal backing store.
    public void clear()
    {
        buffer.clear();
    }

    /// Closes this aggregator and releases the internal backing store.
    ///
    /// This aggregator cannot be used after being closed.
    @Override
    public void close()
    {
        buffer = Retainable.dispose(buffer);
    }

    private void grow(long extraCapacity)
    {
        if (!growable)
            return;
        long capacity = buffer.capacity();
        long newCapacity = Math.max(capacity + capacity / 2, buffer.remaining() + extraCapacity);
        if (newCapacity >= Integer.MAX_VALUE)
            throw new BufferOverflowException();
        RetainableByteBuffer.Mutable newBuffer = RetainableByteBuffer.Mutable.allocate((int)newCapacity, true);
        newBuffer.put(buffer);
        buffer.release();
        buffer = newBuffer;
    }
}
