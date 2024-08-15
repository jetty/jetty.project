//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.compression;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BufferQueueTest
{
    private static final Logger LOG = LoggerFactory.getLogger(BufferQueueTest.class);
    private final AtomicInteger poolCounter = new AtomicInteger();
    private ByteBufferPool pool;

    @BeforeEach
    public void initByteBufferPool()
    {
        pool = new ByteBufferPool.Wrapper(new ArrayByteBufferPool())
        {
            @Override
            public RetainableByteBuffer.Mutable acquire(int size, boolean direct)
            {
                poolCounter.incrementAndGet();
                RetainableByteBuffer.Mutable buf = new RetainableByteBuffer.Mutable.Wrapper(super.acquire(size, direct))
                {
                    @Override
                    public void retain()
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("retain() - buf={} {}", this, relevantStack());
                        super.retain();
                    }

                    @Override
                    public boolean release()
                    {
                        boolean released = super.release();
                        if (LOG.isDebugEnabled())
                            LOG.debug("release() - released={}, buf={} {}", released, this, relevantStack());
                        if (released)
                        {
                            poolCounter.decrementAndGet();
                        }
                        return released;
                    }
                };
                if (LOG.isDebugEnabled())
                    LOG.debug("acquire() - buf={} {}", buf, relevantStack());
                return buf;
            }
        };
    }

    private String relevantStack()
    {
        StackTraceElement[] elems = Thread.currentThread().getStackTrace();
        StringBuilder ret = new StringBuilder();
        for (StackTraceElement elem : elems)
        {
            if (elem.getClassName().startsWith("org.eclipse.jetty.") && !elem.getMethodName().equals("relevantStack"))
            {
                ret.append("\n    ");
                ret.append(elem.getClassName());
                ret.append(".").append(elem.getMethodName());
                ret.append("(").append(elem.getFileName());
                ret.append(":").append(elem.getLineNumber());
                ret.append(")");
            }
        }
        return ret.toString();
    }

    @AfterEach
    public void stopByteBufferPool()
    {
        assertThat(poolCounter.get(), is(0));
    }

    @Test
    public void testEmptyQueue()
    {
        try (BufferQueue queue = new BufferQueue())
        {
            assertNull(queue.getRetainableBuffer());
            assertFalse(queue.hasRemaining());
        }
    }

    @Test
    public void testOneAddedBufferReadFully()
    {
        try (BufferQueue queue = new BufferQueue())
        {
            ByteBuffer buffer = ByteBuffer.wrap("Jetty".getBytes(UTF_8));
            queue.addCopyOf(buffer);

            assertTrue(queue.hasRemaining());

            ByteBuffer active = queue.getBuffer();
            assertNotNull(active);
            String result = UTF_8.decode(active).toString();
            assertThat(result, is("Jetty"));

            assertFalse(queue.hasRemaining());

            active = queue.getBuffer();
            assertNull(active);

            assertFalse(queue.hasRemaining());
        }
    }

    @Test
    public void testOneAddedRetainableBufferReadFully()
    {
        try (BufferQueue queue = new BufferQueue())
        {
            ByteBuffer buffer = ByteBuffer.wrap("Jetty".getBytes(UTF_8));
            queue.addCopyOf(buffer);

            assertTrue(queue.hasRemaining());

            RetainableByteBuffer active = queue.getRetainableBuffer();
            assertNotNull(active);
            String result = UTF_8.decode(active.getByteBuffer()).toString();
            assertThat(result, is("Jetty"));
            active.release(); // we used the buffer, we release it

            assertFalse(queue.hasRemaining());
            active = queue.getRetainableBuffer();
            assertNull(active);
            assertFalse(queue.hasRemaining());
        }
    }

    @Test
    public void testOneAddedBufferReadPartially()
    {
        try (BufferQueue queue = new BufferQueue())
        {
            ByteBuffer buffer = ByteBuffer.wrap("Jetty".getBytes(UTF_8));
            queue.addCopyOf(buffer);

            assertTrue(queue.hasRemaining());

            // get current active buffer
            ByteBuffer active = queue.getBuffer();
            assertNotNull(active);
            assertTrue(queue.hasRemaining());

            // read it partially
            ByteBuffer slice = active.slice();
            slice.limit(3);
            active.position(3);
            String result = UTF_8.decode(slice).toString();
            assertThat(result, is("Jet"));
            assertTrue(queue.hasRemaining());

            // obtain the active buffer again
            active = queue.getBuffer();
            assertNotNull(active);
            assertTrue(queue.hasRemaining());
            result = UTF_8.decode(active).toString();
            assertThat(result, is("ty"));
            assertFalse(queue.hasRemaining());
        }
    }

    @Test
    public void testOneAddedRetainableBufferReadPartially()
    {
        try (BufferQueue queue = new BufferQueue())
        {
            ByteBuffer buffer = ByteBuffer.wrap("Jetty".getBytes(UTF_8));
            queue.addCopyOf(buffer);

            assertTrue(queue.hasRemaining());

            // get current active buffer
            RetainableByteBuffer active = queue.getRetainableBuffer();
            assertNotNull(active);
            assertTrue(queue.hasRemaining());

            // read it partially
            ByteBuffer slice = active.getByteBuffer().slice();
            slice.limit(3);
            active.getByteBuffer().position(3);
            String result = UTF_8.decode(slice).toString();
            assertThat(result, is("Jet"));
            assertTrue(queue.hasRemaining());

            // obtain the active buffer again
            active = queue.getRetainableBuffer();
            assertNotNull(active);
            assertTrue(queue.hasRemaining());
            result = UTF_8.decode(active.getByteBuffer()).toString();
            assertThat(result, is("ty"));
            assertFalse(queue.hasRemaining());
        }
    }

    private static void setBuffer(RetainableByteBuffer buffer, String str)
    {
        BufferUtil.flipToFill(buffer.getByteBuffer());
        buffer.getByteBuffer().put(str.getBytes(UTF_8));
        BufferUtil.flipToFlush(buffer.getByteBuffer(), 0);
    }

    @Test
    public void testThreeAddedBuffers()
    {
        RetainableByteBuffer buf1 = pool.acquire(20, false);
        RetainableByteBuffer buf2 = pool.acquire(20, false);
        RetainableByteBuffer buf3 = pool.acquire(20, false);

        setBuffer(buf1, "Eclipse");
        setBuffer(buf2, " ");
        setBuffer(buf3, "Jetty");

        try (BufferQueue queue = new BufferQueue())
        {
            queue.addCopyOf(buf1);
            queue.addCopyOf(buf2);
            queue.addCopyOf(buf3);

            StringBuilder result = new StringBuilder();
            // read the buffers
            while (queue.hasRemaining())
            {
                ByteBuffer active = queue.getBuffer();
                if (active != null)
                {
                    result.append(UTF_8.decode(active).toString());
                }
            }
            assertFalse(queue.hasRemaining());
            assertEquals("Eclipse Jetty", result.toString());
        }
    }
}
