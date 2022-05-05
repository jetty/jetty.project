//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Promise;

public class ContentSourceByteBuffer implements Runnable
{
    private final ByteBufferAccumulator accumulator = new ByteBufferAccumulator();
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

            if (chunk instanceof Content.Chunk.Error error)
            {
                promise.failed(error.getCause());
                return;
            }

            accumulator.copyBuffer(chunk.getByteBuffer());
            chunk.release();

            if (chunk.isLast())
            {
                promise.succeeded(accumulator.takeByteBuffer());
                return;
            }
        }
    }
}
