//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
    private static class Buf
    {
        public Buf(byte[] buffer, int offset, int length)
        {
            this.buffer = buffer;
            this.offset = offset;
            this.length = length;
        }

        byte[] buffer;
        int offset;
        int length;
    }

    private final int maxSize;
    private int length = 0;
    private List<Buf> buffers;

    public ByteAccumulator(int maxOverallBufferSize)
    {
        this.maxSize = maxOverallBufferSize;
        this.buffers = new ArrayList<>();
    }

    public void addBuffer(byte buf[], int offset, int length)
    {
        if (this.length + length > maxSize)
        {
            throw new MessageTooLargeException("Frame is too large");
        }
        buffers.add(new Buf(buf,offset,length));
        this.length += length;
    }

    public int getLength()
    {
        return length;
    }

    public ByteBuffer getByteBuffer(ByteBufferPool pool)
    {
        ByteBuffer ret = pool.acquire(length,false);
        BufferUtil.clearToFill(ret);

        for (Buf buf : buffers)
        {
            ret.put(buf.buffer, buf.offset, buf.length);
        }

        BufferUtil.flipToFlush(ret,0);
        return ret;
    }
}
