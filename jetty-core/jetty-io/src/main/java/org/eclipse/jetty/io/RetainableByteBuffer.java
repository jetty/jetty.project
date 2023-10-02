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

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.internal.NonRetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

/**
 * <p>A pooled {@link ByteBuffer} which maintains a reference count that is
 * incremented with {@link #retain()} and decremented with {@link #release()}.</p>
 * <p>The {@code ByteBuffer} is released to a {@link ByteBufferPool}
 * when {@link #release()} is called one more time than {@link #retain()};
 * in such case, the call to {@link #release()} returns {@code true}.</p>
 * <p>A {@code RetainableByteBuffer} can either be:</p>
 * <ul>
 *     <li>in pool; in this case {@link #isRetained()} returns {@code false}
 *     and calling {@link #release()} throws {@link IllegalStateException}</li>
 *     <li>out of pool but not retained; in this case {@link #isRetained()}
 *     returns {@code false} and calling {@link #release()} returns {@code true}</li>
 *     <li>out of pool and retained; in this case {@link #isRetained()}
 *     returns {@code true} and calling {@link #release()} returns {@code false}</li>
 * </ul>
 */
public interface RetainableByteBuffer extends Retainable
{
    /**
     * A Zero-capacity, non-retainable {@code RetainableByteBuffer}.
     */
    RetainableByteBuffer EMPTY = wrap(BufferUtil.EMPTY_BUFFER);

    /**
     * <p>Returns a non-retainable {@code RetainableByteBuffer} that wraps
     * the given {@code ByteBuffer}.</p>
     * <p>Use this method to wrap user-provided {@code ByteBuffer}s, or
     * {@code ByteBuffer}s that hold constant bytes, to make them look
     * like {@code RetainableByteBuffer}s.</p>
     * <p>The returned {@code RetainableByteBuffer} {@link #canRetain()}
     * method always returns {@code false}.</p>
     * <p>{@code RetainableByteBuffer}s returned by this method are not
     * suitable to be wrapped in other {@link Retainable} implementations
     * that may delegate calls to {@link #retain()}.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to wrap
     * @return a non-retainable {@code RetainableByteBuffer}
     * @see ByteBufferPool.NonPooling
     */
    static RetainableByteBuffer wrap(ByteBuffer byteBuffer)
    {
        return new NonRetainableByteBuffer(byteBuffer);
    }

    /**
     * <p>Returns a {@code RetainableByteBuffer} that wraps
     * the given {@code ByteBuffer} and {@link Retainable}.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to wrap
     * @param retainable the associated {@link Retainable}.
     * @return a {@code RetainableByteBuffer}
     * @see ByteBufferPool.NonPooling
     */
    static RetainableByteBuffer wrap(ByteBuffer byteBuffer, Retainable retainable)
    {
        return new RetainableByteBuffer()
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return byteBuffer;
            }

            @Override
            public boolean isRetained()
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean canRetain()
            {
                return retainable.canRetain();
            }

            @Override
            public void retain()
            {
                retainable.retain();
            }

            @Override
            public boolean release()
            {
                return retainable.release();
            }
        };
    }

    /**
     * @return whether this instance is retained
     * @see ReferenceCounter#isRetained()
     */
    boolean isRetained();

    /**
     * Get the wrapped, not {@code null}, {@code ByteBuffer}.
     * @return the wrapped, not {@code null}, {@code ByteBuffer}
     */
    ByteBuffer getByteBuffer();

    /**
     * @return whether the {@code ByteBuffer} is direct
     */
    default boolean isDirect()
    {
        return getByteBuffer().isDirect();
    }

    /**
     * @return the number of remaining bytes in the {@code ByteBuffer}
     */
    default int remaining()
    {
        return getByteBuffer().remaining();
    }

    /**
     * @return whether the {@code ByteBuffer} has remaining bytes
     */
    default boolean hasRemaining()
    {
        return getByteBuffer().hasRemaining();
    }

    /**
     * @return the {@code ByteBuffer} capacity
     */
    default int capacity()
    {
        return getByteBuffer().capacity();
    }

    /**
     * @see BufferUtil#clear(ByteBuffer)
     */
    default void clear()
    {
        BufferUtil.clear(getByteBuffer());
    }

    /**
     * A wrapper for {@link RetainableByteBuffer} instances
     */
    class Wrapper extends Retainable.Wrapper implements RetainableByteBuffer
    {
        public Wrapper(RetainableByteBuffer wrapped)
        {
            super(wrapped);
        }

        public RetainableByteBuffer getWrapped()
        {
            return (RetainableByteBuffer)super.getWrapped();
        }

        @Override
        public boolean isRetained()
        {
            return getWrapped().isRetained();
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return getWrapped().getByteBuffer();
        }

        @Override
        public boolean isDirect()
        {
            return getWrapped().isDirect();
        }

        @Override
        public int remaining()
        {
            return getWrapped().remaining();
        }

        @Override
        public boolean hasRemaining()
        {
            return getWrapped().hasRemaining();
        }

        @Override
        public int capacity()
        {
            return getWrapped().capacity();
        }

        @Override
        public void clear()
        {
            getWrapped().clear();
        }
    }
}
