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
        return length;
    }

    public void copyChunk(byte[] buf, int offset, int length)
    {
        copyChunk(BufferUtil.toBuffer(buf, offset, length));
    }

    public void copyChunk(ByteBuffer buffer)
    {
        if (length + buffer.remaining() > maxSize)
        {
            String err = String.format("Resulting message size [%d] is too large for configured max of [%d]", this.length + length, maxSize);
            throw new MessageTooLargeException(err);
        }

        while (buffer.hasRemaining())
        {
            ByteBuffer b = accumulator.getBuffer(buffer.remaining());
            int pos = BufferUtil.flipToFill(b);
            this.length += BufferUtil.put(buffer, b);
            BufferUtil.flipToFlush(b, pos);
        }
    }

    public void transferTo(ByteBuffer buffer)
    {
        if (BufferUtil.space(buffer) < length)
        {
            String err = String.format("Not enough space in ByteBuffer remaining [%d] for accumulated buffers length [%d]", BufferUtil.space(buffer), length);
            throw new IllegalArgumentException(err);
        }

        accumulator.writeTo(buffer);
        close();
    }

    @Override
    public void close()
    {
        length = 0;
        accumulator.close();
    }
}
