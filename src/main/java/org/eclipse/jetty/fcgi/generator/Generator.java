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
    protected ByteBuffer generateContent(FCGI.FrameType frameType, int length)
    {
        return BufferUtil.EMPTY_BUFFER;
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
            this.byteBufferPool = byteBufferPool;
            this.callback = callback;
            this.buffers = new ByteBuffer[frames];
            this.recycles = new boolean[frames];
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

        private void recycle()
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
