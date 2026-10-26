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
import java.util.Objects;

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
public class BufferAggregator implements AutoCloseable
{
    private final ByteBufferPool bufferPool;
    private final boolean direct;
    private final boolean growable;
    private final int initial;
    private RetainableByteBuffer.Mutable buffer;

    public BufferAggregator(boolean growable, int initialCapacity)
    {
        this(null, false, growable, initialCapacity);
    }

    public BufferAggregator(ByteBufferPool bufferPool, boolean direct, boolean growable, int initialCapacity)
    {
        this.bufferPool = Objects.requireNonNullElse(bufferPool, ByteBufferPool.NON_POOLING);
        this.direct = direct;
        this.growable = growable;
        this.initial = initialCapacity;
    }

    /// @return whether this aggregator has bytes
    public boolean hasRemaining()
    {
        return buffer != null && buffer.hasRemaining();
    }

    /// @return how many bytes are present in this aggregator
    public long remaining()
    {
        return buffer == null ? 0 : buffer.remaining();
    }

    /// Appends the given [RetainableByteBuffer] to this aggregator,
    /// growing the backing store if configured so and if necessary.
    ///
    /// @return the number of bytes appended
    public long put(RetainableByteBuffer data)
    {
        if (buffer == null)
            buffer = bufferPool.acquire(initial, direct);
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
        return buffer == null ? "" : buffer.getString(buffer.readPosition(), charset);
    }

    /// Returns the contents of this aggregator as a [RetainableByteBuffer] that must be released.
    ///
    /// This aggregator is modified, and the internal backing store is empty.
    ///
    /// @return the contents of this aggregator as a [RetainableByteBuffer]
    public RetainableByteBuffer take()
    {
        RetainableByteBuffer.Mutable result = buffer;
        buffer = null;
        return result != null ? result : RetainableByteBuffer.empty();
    }

    /// Empties the internal backing store.
    public void clear()
    {
        if (buffer != null)
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
        RetainableByteBuffer.Mutable newBuffer = bufferPool.acquire((int)newCapacity, direct);
        newBuffer.put(buffer);
        buffer.release();
        buffer = newBuffer;
    }
}
