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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;

public class ByteBufferChunk implements Content.Chunk
{
    public static final ByteBufferChunk EMPTY = new ByteBufferChunk(BufferUtil.EMPTY_BUFFER, false, null)
    {
        @Override
        public String toString()
        {
            return "%s[EMPTY]".formatted(ByteBufferChunk.class.getSimpleName());
        }
    };
    public static final ByteBufferChunk EOF = new ByteBufferChunk(BufferUtil.EMPTY_BUFFER, true, null)
    {
        @Override
        public String toString()
        {
            return "%s[EOF]".formatted(ByteBufferChunk.class.getSimpleName());
        }
    };

    private final ByteBuffer byteBuffer;
    private final boolean last;
    private final AtomicReference<Runnable> releaser;

    public ByteBufferChunk(ByteBuffer byteBuffer, boolean last, Runnable releaser)
    {
        this.byteBuffer = Objects.requireNonNull(byteBuffer);
        this.last = last;
        this.releaser = releaser == null ? null : new AtomicReference<>(releaser);
    }

    public ByteBuffer getByteBuffer()
    {
        return byteBuffer;
    }

    public boolean isLast()
    {
        return last;
    }

    public void release()
    {
        if (releaser != null)
        {
            Runnable runnable = releaser.getAndSet(null);
            if (runnable != null)
                runnable.run();
        }
    }

    @Override
    public String toString()
    {
        return "%s@%x[l=%b,b=%s]".formatted(
            getClass().getSimpleName(),
            hashCode(),
            isLast(),
            BufferUtil.toDetailString(getByteBuffer())
        );
    }
}
