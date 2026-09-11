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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class ContentSourceRetainableByteBuffer implements Runnable
{
    private final List<RetainableByteBuffer> _accumulator = new ArrayList<>();
    private final Content.Source _source;
    private final long _maxSize;
    private final Promise<RetainableByteBuffer> _promise;
    private long _size;

    public ContentSourceRetainableByteBuffer(Content.Source source, long maxSize, Promise<RetainableByteBuffer> promise)
    {
        _source = source;
        _maxSize = maxSize;
        _promise = promise;
    }

    @Override
    public void run()
    {
        while (true)
        {
            try (Content.Chunk chunk = _source.read())
            {
                if (chunk == null)
                {
                    _source.demand(this);
                    return;
                }

                if (Content.Chunk.isFailure(chunk))
                {
                    dispose();
                    _promise.failed(chunk.getFailure());
                    if (!chunk.isLast())
                        _source.fail(chunk.getFailure());
                    return;
                }

                if (_maxSize > 0 && _size + chunk.remaining() > _maxSize)
                {
                    dispose();
                    IllegalStateException failure = new IllegalStateException("Max size (" + _maxSize + ") exceeded");
                    _promise.failed(failure);
                    _source.fail(failure);
                    return;
                }

                _size += chunk.remaining();
                _accumulator.add(chunk.acquire());

                if (chunk.isLast())
                {
                    try (RetainableByteBuffer result = RetainableByteBuffer.merge(_accumulator))
                    {
                        dispose();
                        _promise.succeeded(result);
                        return;
                    }
                }
            }
        }
    }

    private void dispose()
    {
        _accumulator.forEach(RetainableByteBuffer::release);
    }
}
