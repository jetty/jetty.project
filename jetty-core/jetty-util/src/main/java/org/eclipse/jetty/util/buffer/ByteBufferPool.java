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

import java.util.Objects;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.buffer.internal.NonPoolingByteBufferPool;

/// A pool for [RetainableByteBuffer] instances.
///
/// [RetainableByteBuffer] that are [acquired][#acquire(int, boolean)]
/// **must** be released by calling [RetainableByteBuffer#release()]
/// otherwise the memory they hold will be leaked.
public interface ByteBufferPool
{
    ByteBufferPool NON_POOLING = new NonPoolingByteBufferPool();
    Sized SIZED_NON_POOLING = new Sized(ByteBufferPool.NON_POOLING);

    /// Acquires a [RetainableByteBuffer.Mutable] from this pool.
    ///
    /// The returned buffer must be [released][RetainableByteBuffer#release()].
    ///
    /// @param size The size of the buffer. The returned buffer will have at least this capacity.
    /// @param direct `true` for a direct memory buffer, `false` for a heap memory buffer.
    /// @return a [RetainableByteBuffer.Mutable]
    RetainableByteBuffer.Mutable acquire(int size, boolean direct);

    /// A wrapper for [ByteBufferPool] instances.
    class Wrapper implements ByteBufferPool
    {
        private final ByteBufferPool wrapped;

        public Wrapper(ByteBufferPool wrapped)
        {
            this.wrapped = wrapped;
        }

        public ByteBufferPool getWrapped()
        {
            return wrapped;
        }

        @Override
        public RetainableByteBuffer.Mutable acquire(int size, boolean direct)
        {
            return getWrapped().acquire(size, direct);
        }
    }

    /// A [ByteBufferPool] with preconfigured size and directness.
    ///
    /// Buffers with preconfigured size and directness can be acquired
    /// using the no-args [#acquire()] method.
    class Sized extends Wrapper
    {
        private final boolean _direct;
        private final int _size;

        /// Creates a sized pool for non-direct buffers of size [IO#DEFAULT_BUFFER_SIZE].
        ///
        /// @param wrapped The [ByteBufferPool] to delegate to
        public Sized(ByteBufferPool wrapped)
        {
            this(wrapped, false, -1);
        }

        /// Creates a sized pool for buffers with the given directness and size.
        ///
        /// @param wrapped The [ByteBufferPool] to delegate to
        /// @param direct `true` for direct buffers, `false` for heap buffers
        /// @param size The buffer size in bytes; zero or negative for [IO#DEFAULT_BUFFER_SIZE]
        public Sized(ByteBufferPool wrapped, boolean direct, int size)
        {
            super(Objects.requireNonNullElse(wrapped, NON_POOLING));
            _direct = direct;
            _size = size > 0 ? size : IO.DEFAULT_BUFFER_SIZE;
        }

        public boolean isDirect()
        {
            return _direct;
        }

        public int getSize()
        {
            return _size;
        }

        /// @return A [RetainableByteBuffer.Mutable] with the preconfigured size and directness.
        public RetainableByteBuffer.Mutable acquire()
        {
            return sizedAcquire(_size, _direct);
        }

        /// @return A [RetainableByteBuffer.Mutable] with the preconfigured directness and the given size.
        /// @param size The buffer size in bytes
        public RetainableByteBuffer.Mutable acquire(int size)
        {
            return sizedAcquire(size, _direct);
        }

        /// @return A [RetainableByteBuffer.Mutable] with the preconfigured size and the given directness.
        /// @param direct `true` for direct buffers, `false` for heap buffers
        public RetainableByteBuffer.Mutable acquire(boolean direct)
        {
            return sizedAcquire(_size, direct);
        }

        protected RetainableByteBuffer.Mutable sizedAcquire(int size, boolean direct)
        {
            return getWrapped().acquire(size, direct);
        }
    }
}
