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

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
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
    private RetainableByteBuffer activeBuffer;

    /**
     * Add a {@link RetainableByteBuffer} to the queue.
     *
     * <p>
     *     Note: The {@code BufferQueue} implementation is responsible
     *     for managing this buffer, including calling {@link RetainableByteBuffer#release()}
     * </p>
     *
     * @param buffer the buffer to add. (must have remaining bytes, this queue is not meant to hold empty buffers)
     */
    public void addCopyOf(RetainableByteBuffer buffer)
    {
        assert buffer.hasRemaining();

        bufferQueue.add(buffer);
    }

    /**
     * Add a {@link ByteBuffer} to the queue, the contents of this buffer
     * will be copied to a new {@link RetainableByteBuffer} for the queue to manage.
     *
     * <p>
     *     Note: The {@code BufferQueue} implementation is responsible
     *     for managing this buffer, including any call to {@link RetainableByteBuffer#release()}
     *     (if need be)
     * </p>
     *
     * @param buffer the buffer to add. (must have remaining bytes, this queue is not meant to hold empty buffers)
     */
    public void addCopyOf(ByteBuffer buffer)
    {
        assert buffer.hasRemaining();

        bufferQueue.add(copyOf(buffer));
    }

    @Override
    public void close()
    {
        releaseActiveBuffer();
        bufferQueue.forEach(RetainableByteBuffer::release);
    }

    public ByteBuffer getBuffer()
    {
        RetainableByteBuffer buffer = getRetainableBuffer();
        if (buffer == null)
            return null;
        return buffer.getByteBuffer();
    }

    /**
     * Get the current active Retainable Buffer.
     * <p>
     *     Note: The {@code BufferQueue} implementation is responsible
     *     for managing this buffer, including calling {@link RetainableByteBuffer#release()}
     * </p>
     * @return the active buffer
     */
    public RetainableByteBuffer getRetainableBuffer()
    {
        if (activeBuffer != null)
        {
            if (activeBuffer.hasRemaining())
                return activeBuffer;
            else
                releaseActiveBuffer();
        }

        activeBuffer = bufferQueue.poll();

        if (activeBuffer != null)
            return activeBuffer;
        return null;
    }

    public boolean hasRemaining()
    {
        if (activeBuffer != null)
        {
            if (activeBuffer.hasRemaining())
                return true;
        }
        RetainableByteBuffer buffer = bufferQueue.peek();
        if (buffer != null)
            return buffer.hasRemaining();
        return false;
    }

    private RetainableByteBuffer copyOf(ByteBuffer buf)
    {
        if (buf == null)
            return null;
        ByteBuffer copy = BufferUtil.allocate(buf.remaining(), buf.isDirect());
        copy.clear();
        copy.put(buf);
        copy.flip();
        return RetainableByteBuffer.wrap(copy);
    }

    private void releaseActiveBuffer()
    {
        if (activeBuffer == null)
            return;
        if (activeBuffer.getRetained() > 0)
            activeBuffer.release();
        activeBuffer = null;
    }
}
