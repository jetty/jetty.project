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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.Promise;

public class ContentSourceRetainableByteBuffer implements Runnable
{
    private final RetainableByteBuffer.Mutable _mutable;
    private final Content.Source _source;
    private final Promise<RetainableByteBuffer> _promise;

    public ContentSourceRetainableByteBuffer(Content.Source source, ByteBufferPool pool, boolean direct, int maxSize, Promise<RetainableByteBuffer> promise)
    {
        _source = source;
        _mutable = new RetainableByteBuffer.Mutable.DynamicCapacity(pool, direct, maxSize);
        _promise = promise;
    }

    @Override
    public void run()
    {
        while (true)
        {
            Content.Chunk chunk = _source.read();

            if (chunk == null)
            {
                _source.demand(this);
                return;
            }

            if (Content.Chunk.isFailure(chunk))
            {
                _promise.failed(chunk.getFailure());
                if (!chunk.isLast())
                    _source.fail(chunk.getFailure());
                return;
            }

            boolean appended = _mutable.append(chunk);
            chunk.release();

            if (!appended)
            {
                IllegalStateException ise = new IllegalStateException("Max size (" + _mutable.capacity() + ") exceeded");
                _promise.failed(ise);
                _mutable.release();
                _source.fail(ise);
                return;
            }

            if (chunk.isLast())
            {
                _promise.succeeded(_mutable);
                _mutable.release();
                return;
            }
        }
    }
}
