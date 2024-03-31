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

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.internal.NonRetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

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
                return retainable.isRetained();
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
     * <p>Returns a {@code RetainableByteBuffer} that wraps
     * the given {@code ByteBuffer} and {@link Runnable} releaser.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to wrap
     * @param releaser a {@link Runnable} to call when the buffer is released.
     * @return a {@code RetainableByteBuffer}
     */
    static RetainableByteBuffer wrap(ByteBuffer byteBuffer, Runnable releaser)
    {
        return new AbstractRetainableByteBuffer(byteBuffer)
        {
            {
                acquire();
            }

            @Override
            public boolean release()
            {
                boolean released = super.release();
                if (released)
                    releaser.run();
                return released;
            }
        };
    }

    default boolean appendTo(ByteBuffer buffer)
    {
        return remaining() == BufferUtil.append(buffer, getByteBuffer());
    }

    default boolean appendTo(RetainableByteBuffer buffer)
    {
        return appendTo(buffer.getByteBuffer());
    }

    /**
     * Creates a deep copy of this RetainableByteBuffer that is entirely independent
     * @return A copy of this RetainableByteBuffer
     */
    default RetainableByteBuffer copy()
    {
        return new AbstractRetainableByteBuffer(BufferUtil.copy(getByteBuffer()))
        {
            {
                acquire();
            }
        };
    }

    /**
     * <p>Copies the bytes from this Chunk to the given byte array.</p>
     *
     * @param bytes the byte array to copy the bytes into
     * @param offset the offset within the byte array
     * @param length the maximum number of bytes to copy
     * @return the number of bytes actually copied
     */
    default int get(byte[] bytes, int offset, int length)
    {
        ByteBuffer b = getByteBuffer();
        if (b == null || !b.hasRemaining())
            return 0;
        length = Math.min(length, b.remaining());
        b.get(bytes, offset, length);
        return length;
    }

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
     * @return whether the {@code ByteBuffer} has remaining bytes left for reading
     */
    default boolean isEmpty()
    {
        return !hasRemaining();
    }

    /**
     * @return whether the {@code ByteBuffer} has remaining bytes left for appending
     */
    default boolean isFull()
    {
        return space() == 0;
    }

    /**
     * Copies the contents of this retainable byte buffer at the end of the given byte buffer.
     * @param toInfillMode the destination buffer.
     * @throws BufferOverflowException – If there is insufficient space in this buffer for the remaining bytes in the source buffer
     * @see ByteBuffer#put(ByteBuffer)
     */
    default void putTo(ByteBuffer toInfillMode) throws BufferOverflowException
    {
        toInfillMode.put(getByteBuffer());
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
     * <p>Skips, advancing the ByteBuffer position, the given number of bytes.</p>
     *
     * @param length the maximum number of bytes to skip
     * @return the number of bytes actually skipped
     */
    default int skip(int length)
    {
        if (length == 0)
            return 0;
        ByteBuffer byteBuffer = getByteBuffer();
        length = Math.min(byteBuffer.remaining(), length);
        byteBuffer.position(byteBuffer.position() + length);
        return length;
    }

    /**
     * Get a slice of the buffer.
     * @return A sliced {@link RetainableByteBuffer} sharing this buffers data and reference count, but
     *         with independent position. The buffer is {@link #retain() retained} by this call.
     */
    default RetainableByteBuffer slice()
    {
        retain();
        return RetainableByteBuffer.wrap(getByteBuffer().slice(), this);
    }

    /**
     * @return the number of bytes left for appending in the {@code ByteBuffer}
     */
    default int space()
    {
        return capacity() - remaining();
    }

    /**
     * Asynchronously copies the contents of this retainable byte buffer into given sink.
     * @param sink the destination sink.
     * @param last true if this is the last write.
     * @param callback the callback to call upon the write completion.
     * @see org.eclipse.jetty.io.Content.Sink#write(boolean, ByteBuffer, Callback)
     */
    default void writeTo(Content.Sink sink, boolean last, Callback callback)
    {
        sink.write(last, getByteBuffer(), callback);
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

        @Override
        public boolean canRetain()
        {
            return getWrapped().canRetain();
        }

        @Override
        public void retain()
        {
            getWrapped().retain();
        }

        @Override
        public boolean release()
        {
            return getWrapped().release();
        }

        @Override
        public String toString()
        {
            return getWrapped().toString();
        }

        @Override
        public boolean appendTo(ByteBuffer buffer)
        {
            return getWrapped().appendTo(buffer);
        }

        @Override
        public boolean appendTo(RetainableByteBuffer buffer)
        {
            return getWrapped().appendTo(buffer);
        }

        @Override
        public RetainableByteBuffer copy()
        {
            return getWrapped().copy();
        }

        @Override
        public int get(byte[] bytes, int offset, int length)
        {
            return getWrapped().get(bytes, offset, length);
        }

        @Override
        public boolean isEmpty()
        {
            return getWrapped().isEmpty();
        }

        @Override
        public boolean isFull()
        {
            return getWrapped().isFull();
        }

        @Override
        public void putTo(ByteBuffer toInfillMode) throws BufferOverflowException
        {
            getWrapped().putTo(toInfillMode);
        }

        @Override
        public int skip(int length)
        {
            return getWrapped().skip(length);
        }

        @Override
        public RetainableByteBuffer slice()
        {
            return getWrapped().slice();
        }

        @Override
        public int space()
        {
            return getWrapped().space();
        }

        @Override
        public void writeTo(Content.Sink sink, boolean last, Callback callback)
        {
            getWrapped().writeTo(sink, last, callback);
        }
    }
}
