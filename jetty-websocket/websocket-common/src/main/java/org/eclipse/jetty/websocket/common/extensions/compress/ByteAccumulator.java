//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.extensions.compress;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.MessageTooLargeException;

/**
 * @deprecated use {@link ByteBufferAccumulator} instead.
 */
@Deprecated
public class ByteAccumulator implements AutoCloseable
{
    private final ByteBufferAccumulator accumulator;
    private final int maxSize;
    private int length = 0;

    public ByteAccumulator(int maxOverallBufferSize)
    {
        this(maxOverallBufferSize, null);
    }

    public ByteAccumulator(int maxOverallBufferSize, ByteBufferPool byteBufferPool)
    {
        this.maxSize = maxOverallBufferSize;
        this.accumulator = new ByteBufferAccumulator(byteBufferPool);
    }

    public int getLength()
    {
        return accumulator.getLength();
    }

    public ByteBuffer ensureBuffer(int minAllocationSize)
    {
        return accumulator.ensureBuffer(minAllocationSize);
    }

    public void readBytes(int read)
    {
        length += read;
        if (length > maxSize)
        {
            String err = String.format("Resulting message size [%d] is too large for configured max of [%d]", length, maxSize);
            throw new MessageTooLargeException(err);
        }
    }

    public void copyChunk(byte[] buf, int offset, int length)
    {
        copyChunk(BufferUtil.toBuffer(buf, offset, length));
    }

    public void copyChunk(ByteBuffer buffer)
    {
        int remaining = buffer.remaining();
        int length = getLength();
        if (length + remaining > maxSize)
        {
            String err = String.format("Resulting message size [%d] is too large for configured max of [%d]", length + remaining, maxSize);
            throw new MessageTooLargeException(err);
        }
        accumulator.copyBuffer(buffer);
    }

    public void transferTo(ByteBuffer buffer)
    {
        // For some reason this method expects the buffer in fill mode but returns a buffer in flush mode.
        BufferUtil.flipToFlush(buffer, 0);

        int availableSpace = BufferUtil.space(buffer);
        int length = getLength();
        if (availableSpace < length)
        {
            String err = String.format("Not enough space in ByteBuffer remaining [%d] for accumulated buffers length [%d]", availableSpace, length);
            throw new IllegalArgumentException(err);
        }

        accumulator.writeTo(buffer);
        close();
    }

    @Override
    public void close()
    {
        accumulator.close();
    }
}
