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
import java.util.function.Consumer;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;

public class ByteBufferChunk implements Content.Chunk
{
    public static final ByteBufferChunk EMPTY = new ByteBufferChunk(BufferUtil.EMPTY_BUFFER, false)
    {
        @Override
        public String toString()
        {
            return "%s[EMPTY]".formatted(ByteBufferChunk.class.getSimpleName());
        }
    };
    public static final ByteBufferChunk EOF = new ByteBufferChunk(BufferUtil.EMPTY_BUFFER, true)
    {
        @Override
        public String toString()
        {
            return "%s[EOF]".formatted(ByteBufferChunk.class.getSimpleName());
        }
    };

    private final ByteBuffer byteBuffer;
    private final boolean last;

    public ByteBufferChunk(ByteBuffer byteBuffer, boolean last)
    {
        this.byteBuffer = Objects.requireNonNull(byteBuffer);
        this.last = last;
    }

    @Override
    public ByteBuffer getByteBuffer()
    {
        return byteBuffer;
    }

    @Override
    public boolean isLast()
    {
        return last;
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

    public static class ReleasedByRunnable extends ByteBufferChunk
    {
        private final AtomicReference<Runnable> releaser;

        public ReleasedByRunnable(ByteBuffer byteBuffer, boolean last, Runnable releaser)
        {
            super(byteBuffer, last);
            this.releaser = new AtomicReference<>(releaser);
        }

        @Override
        public boolean release()
        {
            boolean released = super.release();
            if (released)
            {
                Runnable runnable = releaser.getAndSet(null);
                if (runnable != null)
                    runnable.run();
            }
            return released;
        }
    }

    public static class ReleasedByConsumer extends ByteBufferChunk
    {
        private final AtomicReference<Consumer<ByteBuffer>> releaser;

        public ReleasedByConsumer(ByteBuffer byteBuffer, boolean last, Consumer<ByteBuffer> releaser)
        {
            super(byteBuffer, last);
            this.releaser = new AtomicReference<>(releaser);
        }

        @Override
        public boolean release()
        {
            boolean released = super.release();
            if (released)
            {
                Consumer<ByteBuffer>  consumer = releaser.getAndSet(null);
                if (consumer != null)
                    consumer.accept(getByteBuffer());
            }
            return released;
        }
    }

    public static class ReleasedByRetainable extends ByteBufferChunk
    {
        private final Retainable retainable;

        public ReleasedByRetainable(ByteBuffer byteBuffer, boolean last, Retainable retainable)
        {
            super(byteBuffer, last);
            this.retainable = retainable;
        }

        @Override
        public void retain()
        {
            retainable.retain();
        }

        @Override
        public boolean release()
        {
            return retainable.release();
        }
    }
}
