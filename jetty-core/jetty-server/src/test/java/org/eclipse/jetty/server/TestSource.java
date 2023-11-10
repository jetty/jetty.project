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

package org.eclipse.jetty.server;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.io.Content;

public class TestSource implements Content.Source
{
    private static final Content.Chunk SPURIOUS_WAKEUP = new Content.Chunk()
    {
        @Override
        public ByteBuffer getByteBuffer()
        {
            return null;
        }

        @Override
        public boolean isLast()
        {
            return false;
        }
    };
    private final Queue<Content.Chunk> chunks = new ConcurrentLinkedQueue<>();
    private Runnable demand;

    public TestSource(Content.Chunk... chunks)
    {
        this(Arrays.asList(chunks));
    }

    public TestSource(List<Content.Chunk> chunks)
    {
        if (chunks.isEmpty())
            throw new IllegalArgumentException("At least one chunk is required");
        if (!chunks.get(chunks.size() - 1).isLast())
            throw new IllegalArgumentException("The last chunk must have its last flag set to true");
        for (int i = 0; i < chunks.size(); i++)
        {
            Content.Chunk chunk = chunks.get(i);
            chunk = chunk != null ? chunk : SPURIOUS_WAKEUP;
            if (i < chunks.size() - 1 && chunk.isLast())
                throw new IllegalArgumentException("Non-last chunks must have their flag set to false");
            this.chunks.add(chunk);
        }
    }

    @Override
    public Content.Chunk read()
    {
        if (demand != null)
            throw new IllegalStateException();

        Content.Chunk chunk = chunks.poll();
        if (chunk.isLast())
        {
            if (Content.Chunk.isFailure(chunk))
                chunks.add(chunk);
            else
                chunks.add(Content.Chunk.EOF);
        }
        return chunk == SPURIOUS_WAKEUP ? null : chunk;
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        if (demand != null)
            throw new IllegalStateException();

        if (!chunks.isEmpty())
            demandCallback.run();
        else
            demand = demandCallback;
    }

    @Override
    public void fail(Throwable failure)
    {
        fail(failure, true);
    }

    @Override
    public void fail(Throwable failure, boolean last)
    {
        demand = null;
        while (!chunks.isEmpty())
        {
            Content.Chunk chunk = chunks.poll();
            if (chunk != null)
                chunk.release();
        }
        chunks.add(Content.Chunk.from(failure, last));
    }
}
