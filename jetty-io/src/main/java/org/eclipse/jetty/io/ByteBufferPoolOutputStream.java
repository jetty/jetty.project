//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;

public class ByteBufferPoolOutputStream extends OutputStream
{
    private final ByteBufferPool bufferPool;
    private final ArrayList<ByteBuffer> buffers;
    private final boolean direct;
    private final int acquireSize;

    private ByteBuffer aggregateBuffer;
    private int size = 0;

    public ByteBufferPoolOutputStream(ByteBufferPool bufferPool, int acquireSize, boolean direct)
    {
        this.buffers = new ArrayList<>();
        this.direct = direct;
        this.bufferPool = Objects.requireNonNull(bufferPool);
        this.acquireSize = acquireSize;
        if (acquireSize <= 0)
            throw new IllegalArgumentException();

        this.buffers.add(bufferPool.acquire(acquireSize, direct));
    }

    @Override
    public void write(int b) throws IOException
    {
        write(new byte[]{(byte)b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        write(ByteBuffer.wrap(b, off, len));
    }

    public void write(ByteBuffer data)
    {
        while (data.hasRemaining())
        {
            ByteBuffer buffer = buffers.get(buffers.size() - 1);
            size += BufferUtil.append(buffer, data);
            if (!buffer.hasRemaining())
                buffers.add(bufferPool.acquire(acquireSize, direct));
        }
    }

    public int size()
    {
        return size;
    }

    public ByteBuffer toByteBuffer()
    {
        releaseAggregate();
        aggregateBuffer = bufferPool.acquire(size, direct);
        for (ByteBuffer data : buffers)
        {
            BufferUtil.append(aggregateBuffer, data);
        }
        return aggregateBuffer;
    }

    public byte[] toByteArray()
    {
        return BufferUtil.toArray(toByteBuffer());
    }

    private void releaseAggregate()
    {
        if (aggregateBuffer != null)
        {
            bufferPool.release(aggregateBuffer);
            aggregateBuffer = null;
        }
    }

    @Override
    public void close()
    {
        releaseAggregate();
        for (ByteBuffer buffer : buffers)
            bufferPool.release(buffer);
    }
}
