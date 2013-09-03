//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.fcgi.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class Generator
{
    public static final int MAX_CONTENT_LENGTH = 0xFF_FF;

    protected final ByteBufferPool byteBufferPool;

    public Generator(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
    }

    protected Result generateContent(int id, ByteBuffer content, boolean lastContent, Callback callback, FCGI.FrameType frameType)
    {
        id &= 0xFF_FF;

        int remaining = content == null ? 0 : content.remaining();
        int frames = 2 * (remaining / MAX_CONTENT_LENGTH + 1) + (lastContent ? 1 : 0);
        Result result = new Result(byteBufferPool, callback, frames);

        while (remaining > 0 || lastContent)
        {
            ByteBuffer buffer = byteBufferPool.acquire(8, false);
            BufferUtil.clearToFill(buffer);
            result.add(buffer, true);

            // Generate the frame header
            buffer.put((byte)0x01);
            buffer.put((byte)frameType.code);
            buffer.putShort((short)id);
            int length = Math.min(MAX_CONTENT_LENGTH, remaining);
            buffer.putShort((short)length);
            buffer.putShort((short)0);
            buffer.flip();

            if (remaining == 0)
                break;

            // Slice to content to avoid copying
            int limit = content.limit();
            content.limit(content.position() + length);
            ByteBuffer slice = content.slice();
            result.add(slice, false);
            content.position(content.limit());
            content.limit(limit);
            remaining -= length;
        }

        return result;
    }

    public static class Result implements Callback
    {
        private final ByteBufferPool byteBufferPool;
        private final Callback callback;
        private final ByteBuffer[] buffers;
        private final boolean[] recycles;
        private int index;

        public Result(ByteBufferPool byteBufferPool, Callback callback, int frames)
        {
            this(byteBufferPool, callback, new ByteBuffer[frames], new boolean[frames], 0);
        }

        protected Result(Result that)
        {
            this(that.byteBufferPool, that.callback, that.buffers, that.recycles, that.index);
        }
        
        private Result(ByteBufferPool byteBufferPool, Callback callback, ByteBuffer[] buffers, boolean[] recycles, int index)
        {
            this.byteBufferPool = byteBufferPool;
            this.callback = callback;
            this.buffers = buffers;
            this.recycles = recycles;
            this.index = index;
        }

        public void add(ByteBuffer buffer, boolean recycle)
        {
            buffers[index] = buffer;
            recycles[index] = recycle;
            ++index;
        }

        public ByteBuffer[] getByteBuffers()
        {
            return buffers;
        }

        @Override
        public void succeeded()
        {
            recycle();
            callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            recycle();
            callback.failed(x);
        }

        protected void recycle()
        {
            for (int i = 0; i < buffers.length; ++i)
            {
                ByteBuffer buffer = buffers[i];
                if (recycles[i])
                    byteBufferPool.release(buffer);
            }
        }
    }
}
