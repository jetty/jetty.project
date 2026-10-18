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

/// Appends [RetainableByteBuffer]s by copying them into a single [RetainableByteBuffer] backing store.
///
/// This aggregator can grow the backing store to a configurable maximum.
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
    private final int initialCapacity;
    private final long maxCapacity;
    private RetainableByteBuffer.Mutable buffer;

    public BufferAggregator(boolean growable, int initialCapacity)
    {
        this(null, false, growable, initialCapacity);
    }

    public BufferAggregator(ByteBufferPool bufferPool, boolean direct, boolean growable, int initialCapacity)
    {
        this(bufferPool, direct, growable, initialCapacity, -1);
    }

    /// @param bufferPool the pool to acquire the backing store from, or `null` for a non-pooling one
    /// @param direct whether the backing store uses direct memory
    /// @param growable whether the backing store can grow
    /// @param initialCapacity the initial backing store capacity
    /// @param maxCapacity the max number of bytes that can be aggregated, or a negative value for no limit
    public BufferAggregator(ByteBufferPool bufferPool, boolean direct, boolean growable, int initialCapacity, long maxCapacity)
    {
        if (maxCapacity >= 0 && initialCapacity > maxCapacity)
            throw new IllegalArgumentException("initialCapacity (" + initialCapacity + ") must be <= maxCapacity (" + maxCapacity + ")");
        this.bufferPool = Objects.requireNonNullElse(bufferPool, ByteBufferPool.NON_POOLING);
        this.direct = direct;
        this.growable = growable;
        this.initialCapacity = initialCapacity;
        this.maxCapacity = maxCapacity;
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
    /// The bytes that cannot be appended, because the backing store cannot
    /// grow or because the max capacity is reached, are left in the given
    /// [RetainableByteBuffer], which is consumed by the number of bytes appended.
    ///
    /// @param data the [RetainableByteBuffer] to copy the bytes from
    /// @return the number of bytes appended
    public long append(RetainableByteBuffer data)
    {
        if (buffer == null)
            buffer = bufferPool.acquire(initialCapacity, direct);
        long remaining = data.remaining();
        if (buffer.space() < remaining)
            grow(remaining);

        // The backing store may have more space than allowed by
        // the max capacity, as the pool may round up the capacity.
        long allowed = maxCapacity - buffer.remaining();
        if (maxCapacity < 0 || remaining <= allowed)
            return buffer.append(data);

        try (RetainableByteBuffer slice = data.slice(allowed))
        {
            // The backing store may have less space than
            // allowed, consume only what has been appended.
            long appended = buffer.append(slice);
            data.consume(appended);
            return appended;
        }
    }

    /// Reads the contents of this aggregator as a [String] using the given [Charset].
    ///
    /// This aggregator is left unmodified.
    ///
    /// @return the contents of this aggregator as a [String]
    public String getString(Charset charset)
    {
        return buffer == null ? "" : buffer.getString(buffer.readPosition(), charset);
    }

    /// Takes the contents of this aggregator as a [RetainableByteBuffer] that must be released.
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
        if (maxCapacity >= 0 && capacity >= maxCapacity)
            return;
        long newCapacity = Math.max(capacity + capacity / 2, buffer.remaining() + extraCapacity);
        if (maxCapacity >= 0)
            newCapacity = Math.min(newCapacity, maxCapacity);
        if (newCapacity >= Integer.MAX_VALUE)
            throw new BufferOverflowException();
        RetainableByteBuffer.Mutable newBuffer = bufferPool.acquire((int)newCapacity, direct);
        newBuffer.put(buffer);
        buffer.release();
        buffer = newBuffer;
    }
}
