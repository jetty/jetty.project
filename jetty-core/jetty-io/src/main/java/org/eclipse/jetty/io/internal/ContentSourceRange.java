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

import java.nio.channels.ClosedChannelException;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.TypeUtil;

/**
 * A {@link Content.Source} that provides a range of content from another {@link Content.Source}.
 */
public class ContentSourceRange implements Content.Source
{
    private final long _offset;
    private final long _length;
    private final Content.Source _source;
    private final boolean _readToEof;
    private long _offsetRemaining;
    private long _lengthRemaining;
    private Content.Chunk _terminal;

    /**
     * Create a {@link ContentSourceRange} which wraps another {@link Content.Source} to appear
     * as a sub-range of the original.
     *
     * @param source The {@link Content.Source} to wrap.
     * @param offset the offset byte of the content to start from.
     *               Must be greater than or equal to 0 and less than the content length (if known).
     * @param length the length of the content to make available, -1 for the full length.
     *               If the size of the content is known, the length may be truncated to the content size minus the offset.
     * @throws IndexOutOfBoundsException if the offset or length are out of range.
     * @see TypeUtil#checkOffsetLengthSize(long, long, long)
     */
    public ContentSourceRange(Content.Source source, long offset, long length)
    {
        this(source, offset, length, false);
    }

    /**
     * Create a {@link ContentSourceRange} which wraps another {@link Content.Source} to appear
     * as a sub-range of the original.
     *
     * @param source The {@link Content.Source} to wrap.
     * @param offset the offset byte of the content to start from.
     *               Must be greater than or equal to 0 and less than the content length (if known).
     * @param length the length of the content to make available, -1 for the full length.
     *               If the size of the content is known, the length may be truncated to the content size minus the offset.
     * @param readToEof - if true, read until EOF of the source after the range is read.
     * @throws IndexOutOfBoundsException if the offset or length are out of range.
     * @see TypeUtil#checkOffsetLengthSize(long, long, long)
     */
    public ContentSourceRange(Content.Source source, long offset, long length, boolean readToEof)
    {
        _source = source;
        _offset = offset;
        _length = TypeUtil.checkOffsetLengthSize(offset, length, source.getLength());
        _offsetRemaining = _offset;
        _lengthRemaining = _length;
        _readToEof = readToEof;
    }

    @Override
    public long getLength()
    {
        return _length;
    }

    @Override
    public Content.Chunk read()
    {
        if (_terminal != null)
        {
            Content.Chunk chunk = _terminal;
            _terminal = Content.Chunk.next(chunk);
            return chunk;
        }

        while (true)
        {
            Content.Chunk chunk = _source.read();
            if (chunk == null)
                return null;

            if (Content.Chunk.isFailure(chunk))
            {
                _terminal = Content.Chunk.next(chunk);
                return chunk;
            }

            if (_offsetRemaining > 0)
            {
                if (_offsetRemaining >= chunk.remaining())
                {
                    // We can skip this whole chunk.
                    _offsetRemaining -= chunk.remaining();
                    chunk.release();
                    if (chunk.isLast())
                        return _terminal = Content.Chunk.EOF;
                    continue;
                }
                else
                {
                    // Advance position to the correct offset.
                    RetainableByteBuffer slice = chunk.slice();
                    chunk.release();
                    while (_offsetRemaining > 0)
                        _offsetRemaining -= slice.skip(_offsetRemaining);
                    chunk = Content.Chunk.from(slice, chunk.isLast());
                }
            }

            // We can start processing the limited length if we have reached the starting offset and there
            // is length remaining
            if (_lengthRemaining == 0)
            {
                // We have read all we need to
                if (_readToEof)
                {
                    // Release the chunk and continue until we find the last chunk.
                    chunk.release();
                    if (chunk.isLast())
                        return _terminal = Content.Chunk.EOF;
                    continue;
                }

                // We do not have to read until EOF of the source, so we can return EOF now and fail the source.
                fail(new ClosedChannelException());
                return _terminal = Content.Chunk.EOF;
            }

            if (_lengthRemaining > 0 && _lengthRemaining < chunk.remaining())
            {
                // We must limit the size of the chunk to the remaining length.
                RetainableByteBuffer slice = chunk.slice(_lengthRemaining);
                _lengthRemaining = 0;
                chunk.release();
                return Content.Chunk.from(slice, chunk.isLast());
            }

            // We can return the whole chunk.
            _lengthRemaining -= chunk.remaining();
            return chunk;
        }
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        _source.demand(demandCallback);
    }

    @Override
    public void fail(Throwable failure)
    {
        _source.fail(failure);
    }

    @Override
    public void fail(Throwable failure, boolean last)
    {
        _source.fail(failure, last);
    }

    @Override
    public boolean rewind()
    {
        boolean rewound = _source.rewind();
        if (rewound)
        {
            _offsetRemaining = _offset;
            _lengthRemaining = _length;
        }
        return rewound;
    }

    @Override
    public String toString()
    {
        return String.format("%s{off=%s, len=%s, source=%s}", getClass().getSimpleName(), _offset, _length, _source);
    }
}
