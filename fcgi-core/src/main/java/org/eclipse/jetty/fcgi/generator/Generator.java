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

    protected Result generateContent(int id, ByteBuffer content, boolean recycle, boolean lastContent, Callback callback, FCGI.FrameType frameType)
    {
        id &= 0xFF_FF;

        int remaining = content == null ? 0 : content.remaining();
        Result result = new Result(byteBufferPool, callback, null, false);

        while (remaining > 0 || lastContent)
        {
            ByteBuffer buffer = byteBufferPool.acquire(8, false);
            BufferUtil.clearToFill(buffer);
            result = result.append(buffer, true);

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
            result = result.append(slice, recycle);
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
        private final ByteBuffer buffer;
        private final boolean recycle;
        private final Result previous;

        public Result(ByteBufferPool byteBufferPool, Callback callback, ByteBuffer buffer, boolean recycle)
        {
            this(byteBufferPool, callback, buffer, recycle, null);
        }

        private Result(ByteBufferPool byteBufferPool, Callback callback, ByteBuffer buffer, boolean recycle, Result previous)
        {
            this.byteBufferPool = byteBufferPool;
            this.callback = callback;
            this.buffer = buffer;
            this.recycle = recycle;
            this.previous = previous;
        }

        public Result append(ByteBuffer buffer, boolean recycle)
        {
            return new Result(byteBufferPool, null, buffer, recycle, this);
        }

        public ByteBuffer[] getByteBuffers()
        {
            return getByteBuffers(0);
        }

        private ByteBuffer[] getByteBuffers(int length)
        {
            int newLength = length + (buffer == null ? 0 : 1);
            ByteBuffer[] result;
            result = previous != null ? previous.getByteBuffers(newLength) : new ByteBuffer[newLength];
            if (buffer != null)
                result[result.length - newLength] = buffer;
            return result;
        }

        @Override
        public void succeeded()
        {
            recycle();
            if (previous != null)
                previous.succeeded();
            if (callback != null)
                callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            recycle();
            if (previous != null)
                previous.failed(x);
            if (callback != null)
                callback.failed(x);
        }

        protected void recycle()
        {
            if (recycle)
                byteBufferPool.release(buffer);
        }
    }
}
