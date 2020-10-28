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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.MessageTooLargeException;

public class ByteAccumulator
{
    private final List<ByteBuffer> chunks = new ArrayList<>();
    private final int maxSize;
    private int length = 0;
    private final ByteBufferPool bufferPool;

    public ByteAccumulator(int maxOverallBufferSize)
    {
        this(maxOverallBufferSize, null);
    }

    public ByteAccumulator(int maxOverallBufferSize, ByteBufferPool bufferPool)
    {
        this.maxSize = maxOverallBufferSize;
        this.bufferPool = bufferPool;
    }
    
    public void copyChunk(ByteBuffer buf)
    {
        int length = buf.remaining();
        if (this.length + length > maxSize)
        {
            release(buf);
            String err = String.format("Resulting message size [%,d] is too large for configured max of [%,d]", this.length + length, maxSize);
            throw new MessageTooLargeException(err);
        }
        
        if (buf.hasRemaining())
        {
            chunks.add(buf);
            this.length += length;
        }
        else
        {
            // release 0 length buffer directly
            release(buf);
        }
    }

    public void copyChunk(byte[] buf, int offset, int length)
    {
        if (this.length + length > maxSize)
        {
            String err = String.format("Resulting message size [%,d] is too large for configured max of [%,d]", this.length + length, maxSize);
            throw new MessageTooLargeException(err);
        }
        chunks.add(ByteBuffer.wrap(buf, offset, length));
        this.length += length;
    }

    public int getLength()
    {
        return length;
    }

    int getMaxSize() 
    {
        return maxSize;
    }

    ByteBuffer newByteBuffer(int size)
    {
        if (bufferPool == null) 
        {
            return ByteBuffer.allocate(size);
        }
        return (ByteBuffer)bufferPool.acquire(size, false).clear();
    }

    public void transferTo(ByteBuffer buffer)
    {
        if (buffer.remaining() < length)
        {
            throw new IllegalArgumentException(String.format("Not enough space in ByteBuffer remaining [%d] for accumulated buffers length [%d]",
                buffer.remaining(), length));
        }

        int position = buffer.position();
        for (ByteBuffer chunk : chunks)
        {
            buffer.put(chunk);
        }
        BufferUtil.flipToFlush(buffer, position);
    }
    
    void recycle() 
    {
        length = 0;
        
        for (ByteBuffer chunk : chunks)
        { 
            release(chunk);
        }
        
        chunks.clear();
    }
    
    void release(ByteBuffer buffer)
    {
        if (bufferPool != null)
        {
            bufferPool.release(buffer);
        }
    }
}
