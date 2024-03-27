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

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.Promise;

public class ContentSourceByteBuffer implements Runnable
{
    private final RetainableByteBuffer.Mutable.Accumulator accumulator = new RetainableByteBuffer.Mutable.Accumulator(null, false, -1);
    private final Content.Source source;
    private final Promise<ByteBuffer> promise;

    public ContentSourceByteBuffer(Content.Source source, Promise<ByteBuffer> promise)
    {
        this.source = source;
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
                RetainableByteBuffer copy = accumulator.copy();
                accumulator.release();

                // We know the accumulator is not using a pool, so whilst we release after succeeded, it is safe
                // for the promise to retain the ByteBuffer after the call.
                promise.succeeded(copy.getByteBuffer());
                copy.release();
                return;
            }
        }
    }
}
