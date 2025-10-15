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

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.TypeUtil;

public class ContentSourceRange implements Content.Source
{
    private final long _offset;
    private final long _length;
    private final Content.Source _source;
    long _offsetRemaining;
    long _lengthRemaining;

    public ContentSourceRange(Content.Source source, long offset, long length)
    {
        _source = source;
        _offset = offset;
        _length = TypeUtil.checkOffsetLengthSize(offset, length, source.getLength());
        _offsetRemaining = _offset;
        _lengthRemaining = _length;
    }

    @Override
    public long getLength()
    {
        return _length;
    }

    @Override
    public Content.Chunk read()
    {
        while (true)
        {
            Content.Chunk chunk = _source.read();
            if (chunk == null)
                return null;

            if (_offsetRemaining > 0)
            {
                if (_offsetRemaining >= chunk.remaining())
                {
                    // We can skip this whole chunk.
                    _offsetRemaining -= chunk.remaining();
                    if (chunk.isLast())
                    {
                        chunk.clear();
                        return chunk;
                    }

                    chunk.release();
                    continue;
                }
                else
                {
                    // Advance position to the correct offset.
                    chunk.skip(_offsetRemaining);
                    _offsetRemaining = 0;
                }
            }

            // We can start processing the limited length if we have reached the starting offset.
            if (_offsetRemaining == 0 && _lengthRemaining >= 0)
            {
                if (_lengthRemaining == 0)
                {
                    // Release the chunk and continue until we find a last chunk.
                    if (!chunk.isLast())
                    {
                        chunk.release();
                        continue;
                    }

                    chunk.clear();
                }
                else if (_lengthRemaining >= chunk.remaining())
                {
                    // We can take the whole chunk.
                    _lengthRemaining -= chunk.remaining();
                }
                else if (_lengthRemaining < chunk.remaining())
                {
                    // We must limit the size of the chunk to the remaining length.
                    chunk.limit(_lengthRemaining);
                    _lengthRemaining = 0;
                }
            }

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
