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

package org.eclipse.jetty.io.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.TypeUtil;

/**
 * <p>A {@link Content.Source} backed by a  {@link SeekableByteChannel}.
 */
public class SeekableByteChannelContentSource extends ByteChannelContentSource implements Content.Source.Seekable
{
    private long _position;

    /**
     * Create a new instance that reads from a {@link SeekableByteChannel}.
     *
     * @param byteBufferPool The {@link ByteBufferPool.Sized} to use for any internal buffers.
     * @param byteChannel The {@link SeekableByteChannel}s to use as the source.
     */
    public SeekableByteChannelContentSource(ByteBufferPool.Sized byteBufferPool, SeekableByteChannel byteChannel)
    {
        this(byteBufferPool, byteChannel, 0L, -1L);
    }

    /**
     * Create a new instance that reads from a {@link SeekableByteChannel}.
     *
     * @param byteBufferPool The {@link ByteBufferPool.Sized} to use for any internal buffers.
     * @param byteChannel The {@link SeekableByteChannel}s to use as the source.
     * @param offset the position to start reading from.
     * Must be greater than or equal to 0 and less than the content length (if known).
     * @param length the length of the content to make available, -1 for the full length.
     * If the size of the content is known, the length may be truncated to the content size minus the position.
     * @throws IndexOutOfBoundsException if the position or length are out of range.
     * @see TypeUtil#checkOffsetLengthSize(long, long, long)
     */
    public SeekableByteChannelContentSource(ByteBufferPool.Sized byteBufferPool, SeekableByteChannel byteChannel, long offset, long length)
    {
        super(byteBufferPool, byteChannel, offset, length);
        _position = offset;
    }

    @Override
    public SeekableByteChannel getByteChannel()
    {
        return (SeekableByteChannel)super.getByteChannel();
    }

    @Override
    protected Content.Chunk skipToOffset()
    {
        position(getOffset());
        return Content.Chunk.EMPTY;
    }

    @Override
    public long position()
    {
        return _position;
    }

    @Override
    public void position(long position)
    {
        try
        {
            if (position < 0)
                throw new IllegalArgumentException("invalid position " + position);
            _position = position;
            SeekableByteChannel seekable = getByteChannel();
            if (seekable != null)
                seekable.position(position);
        }
        catch (IOException x)
        {
            throw new UncheckedIOException(x);
        }
    }

    @Override
    public long remaining()
    {
        long length = getLength();
        return length < 0 ? -1 : length - _position + getOffset();
    }

    @Override
    public Seekable slice(long position, int length)
    {
        // TODO: check position and length
        return new SeekableByteChannelContentSource(getByteBufferPool(), getByteChannel(), position, length);
    }

    @Override
    public boolean rewind()
    {
        // TODO
        return false;
    }
}
