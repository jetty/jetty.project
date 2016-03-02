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

package org.eclipse.jetty.fcgi.generator;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

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

        int contentLength = content == null ? 0 : content.remaining();
        Result result = new Result(byteBufferPool, callback);

        while (contentLength > 0 || lastContent)
        {
            ByteBuffer buffer = byteBufferPool.acquire(8, false);
            BufferUtil.clearToFill(buffer);
            result = result.append(buffer, true);

            // Generate the frame header
            buffer.put((byte)0x01);
            buffer.put((byte)frameType.code);
            buffer.putShort((short)id);
            int length = Math.min(MAX_CONTENT_LENGTH, contentLength);
            buffer.putShort((short)length);
            buffer.putShort((short)0);
            BufferUtil.flipToFlush(buffer, 0);

            if (contentLength == 0)
                break;

            // Slice the content to avoid copying
            int limit = content.limit();
            content.limit(content.position() + length);
            ByteBuffer slice = content.slice();
            // Don't recycle the slice
            result = result.append(slice, false);
            content.position(content.limit());
            content.limit(limit);
            contentLength -= length;
            // Recycle the content buffer if needed
            if (recycle && contentLength == 0)
                result = result.append(content, true);
        }

        return result;
    }

    // TODO: rewrite this class in light of ByteBufferPool.Lease.
    public static class Result implements Callback
    {
        private final List<Callback> callbacks = new ArrayList<>(2);
        private final List<ByteBuffer> buffers = new ArrayList<>(8);
        private final List<Boolean> recycles = new ArrayList<>(8);
        private final ByteBufferPool byteBufferPool;

        public Result(ByteBufferPool byteBufferPool, Callback callback)
        {
            this.byteBufferPool = byteBufferPool;
            this.callbacks.add(callback);
        }

        public Result append(ByteBuffer buffer, boolean recycle)
        {
            if (buffer != null)
            {
                buffers.add(buffer);
                recycles.add(recycle);
            }
            return this;
        }

        public Result join(Result that)
        {
            callbacks.addAll(that.callbacks);
            buffers.addAll(that.buffers);
            recycles.addAll(that.recycles);
            return this;
        }

        public ByteBuffer[] getByteBuffers()
        {
            return buffers.toArray(new ByteBuffer[buffers.size()]);
        }

        @Override
        @SuppressWarnings("ForLoopReplaceableByForEach")
        public void succeeded()
        {
            recycle();
            for (int i = 0; i < callbacks.size(); ++i)
            {
                Callback callback = callbacks.get(i);
                if (callback != null)
                    callback.succeeded();
            }
        }

        @Override
        @SuppressWarnings("ForLoopReplaceableByForEach")
        public void failed(Throwable x)
        {
            recycle();
            for (int i = 0; i < callbacks.size(); ++i)
            {
                Callback callback = callbacks.get(i);
                if (callback != null)
                    callback.failed(x);
            }
        }

        protected void recycle()
        {
            for (int i = 0; i < buffers.size(); ++i)
            {
                ByteBuffer buffer = buffers.get(i);
                if (recycles.get(i))
                    byteBufferPool.release(buffer);
            }
        }
    }
}
