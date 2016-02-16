//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.MessageTooLargeException;

public class ByteAccumulator
{
    private final List<byte[]> chunks = new ArrayList<>();
    private final int maxSize;
    private int length = 0;

    public ByteAccumulator(int maxOverallBufferSize)
    {
        this.maxSize = maxOverallBufferSize;
    }

    public void copyChunk(byte buf[], int offset, int length)
    {
        if (this.length + length > maxSize)
        {
            throw new MessageTooLargeException("Frame is too large");
        }

        byte copy[] = new byte[length - offset];
        System.arraycopy(buf,offset,copy,0,length);

        chunks.add(copy);
        this.length += length;
    }
    
    public int getLength()
    {
        return length;
    }

    public void transferTo(ByteBuffer buffer)
    {
        if (buffer.remaining() < length)
        {
            throw new IllegalArgumentException(String.format("Not enough space in ByteBuffer remaining [%d] for accumulated buffers length [%d]",
                    buffer.remaining(),length));
        }

        int position = buffer.position();
        for (byte[] chunk : chunks)
        {
            buffer.put(chunk,0,chunk.length);
        }
        BufferUtil.flipToFlush(buffer,position);
    }
}
