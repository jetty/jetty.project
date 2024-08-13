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

package org.eclipse.jetty.compression;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link org.eclipse.jetty.io.RetainableByteBuffer} queue used by some
 * compression {@link org.eclipse.jetty.compression.Compression.Encoder}
 * implementations to handle output buffers.
 */
public class BufferQueue implements Closeable
{
    private static final Logger LOG = LoggerFactory.getLogger(BufferQueue.class);
    private final Queue<RetainableByteBuffer> bufferQueue = new ArrayDeque<>();
    private final ByteBufferPool byteBufferPool;
    private RetainableByteBuffer activeBuffer;

    public BufferQueue(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
    }

    public void add(RetainableByteBuffer buffer)
    {
        buffer.retain();
        bufferQueue.add(buffer);
    }

    public void add(ByteBuffer buffer)
    {
        add(copyOf(buffer));
    }

    @Override
    public void close()
    {
        if (activeBuffer != null)
            activeBuffer.release();
        bufferQueue.forEach(RetainableByteBuffer::release);
    }

    public RetainableByteBuffer getRetainableBuffer()
    {
        if (activeBuffer != null && !activeBuffer.hasRemaining())
        {
            activeBuffer.release();
            activeBuffer = null;
        }

        if (activeBuffer == null)
            activeBuffer = bufferQueue.poll();

        if (activeBuffer != null)
            return activeBuffer;
        return null;
    }

    public ByteBuffer getBuffer()
    {
        RetainableByteBuffer buffer = getRetainableBuffer();
        if (buffer == null)
            return null;
        return buffer.getByteBuffer();
    }

    public boolean hasRemaining()
    {
        if (activeBuffer != null && activeBuffer.hasRemaining())
            return true;

        return !bufferQueue.isEmpty();
    }

    private RetainableByteBuffer copyOf(ByteBuffer buf)
    {
        if (buf == null)
            return null;
        RetainableByteBuffer.Mutable copy = byteBufferPool.acquire(buf.remaining(), buf.isDirect());
        copy.getByteBuffer().clear();
        copy.getByteBuffer().put(buf);
        copy.getByteBuffer().flip();
        return copy;
    }
}
