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
    private final RetainableByteBuffer.Accumulator accumulator;
    private final Content.Source source;
    private final Promise<RetainableByteBuffer> promise;

    public ContentSourceRetainableByteBuffer(Content.Source source, ByteBufferPool pool, boolean direct, int maxSize, Promise<RetainableByteBuffer> promise)
    {
        this.source = source;
        this.accumulator = new RetainableByteBuffer.Accumulator(pool, direct, maxSize);
        this.promise = promise;
    }

    @Override
    public void run()
    {
        while (true)
        {
            Content.Chunk chunk = source.read();

            if (chunk == null)
            {
                source.demand(this);
                return;
            }

            if (Content.Chunk.isFailure(chunk))
            {
                promise.failed(chunk.getFailure());
                if (!chunk.isLast())
                    source.fail(chunk.getFailure());
                return;
            }

            accumulator.append(chunk);
            chunk.release();

            if (chunk.isLast())
            {
                promise.succeeded(accumulator);
                accumulator.release();
                return;
            }
        }
    }
}
