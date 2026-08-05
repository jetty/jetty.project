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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ByteArrayEndPointTest
{
    private Scheduler _scheduler;

    @BeforeEach
    public void before() throws Exception
    {
        _scheduler = new ScheduledExecutorScheduler();
        _scheduler.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _scheduler.stop();
    }

    @Test
    public void testClose() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();

        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(1024, false);
        assertEquals(0, endPoint.fill(buffer));

        endPoint.close();

        IOException failure = assertThrows(IOException.class, () -> endPoint.fill(buffer));
        assertThat(failure.getMessage(), containsString("CLOSED"));
    }

    @Test
    public void testFill() throws Exception
    {
        try (ByteArrayEndPoint endPoint = new ByteArrayEndPoint())
        {
            endPoint.addInput("test input");

            RetainableByteBuffer.Mutable buffer1 = RetainableByteBuffer.Mutable.allocate(1024, false);

            assertEquals(10, endPoint.fill(buffer1));
            assertEquals("test input", BufferUtil.toString(buffer1));
            assertEquals(0, endPoint.fill(buffer1));

            endPoint.addInput(" more");
            assertEquals(5, endPoint.fill(buffer1));
            assertEquals("test input more", BufferUtil.toString(buffer1));
            assertEquals(0, endPoint.fill(buffer1));

            endPoint.addInput((RetainableByteBuffer)null);
            assertEquals(-1, endPoint.fill(buffer1));

            endPoint.reset();
            endPoint.addInput("and more");

            RetainableByteBuffer.Mutable buffer2 = RetainableByteBuffer.Mutable.allocate(4, false);

            assertEquals(4, endPoint.fill(buffer2));
            assertEquals("and ", buffer2.getString(UTF_8));
            assertEquals(0, endPoint.fill(buffer2));
            buffer2.compact();
            assertEquals(4, endPoint.fill(buffer2));
            assertEquals("more", buffer2.getString(UTF_8));
        }
    }

    @Test
    public void testGrowingFlush() throws Exception
    {
        try (ByteArrayEndPoint endPoint = new ByteArrayEndPoint(null, 0, null, 15, true))
        {
            assertTrue(endPoint.flush(RetainableByteBuffer.wrap("some output", UTF_8)));
            assertEquals("some output", endPoint.getOutputString(UTF_8));

            assertTrue(endPoint.flush(RetainableByteBuffer.wrap(" some more", UTF_8)));
            assertEquals("some output some more", endPoint.getOutputString(UTF_8));

            assertTrue(endPoint.flush(RetainableByteBuffer.Mutable.empty()));
            assertEquals("some output some more", endPoint.getOutputString(UTF_8));

            assertTrue(endPoint.flush(RetainableByteBuffer.wrap(RetainableByteBuffer.Mutable.empty(), RetainableByteBuffer.wrap(" and", UTF_8), RetainableByteBuffer.wrap(" more", UTF_8))));
            assertEquals("some output some more and more", endPoint.getOutputString(UTF_8));
        }
    }

    @Test
    public void testFlush() throws Exception
    {
        try (ByteArrayEndPoint endPoint = new ByteArrayEndPoint((byte[])null, 10))
        {
            RetainableByteBuffer data = RetainableByteBuffer.wrap("Some more data.", UTF_8);
            assertFalse(endPoint.flush(data));
            assertEquals("Some more ", endPoint.getOutputString(UTF_8));
            assertEquals("data.", data.getString(data.readPosition(), UTF_8));
            assertEquals("Some more ", endPoint.takeOutputString());
            assertTrue(endPoint.flush(data));
            assertEquals("data.", endPoint.takeOutputString());
        }
    }

    @Test
    public void testReadable() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(_scheduler, 5000);
        endPoint.addInput("test input");

        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(1024, false);
        FutureCallback fcb = new FutureCallback();

        endPoint.fillInterested(fcb);
        fcb.get(100, TimeUnit.MILLISECONDS);
        assertTrue(fcb.isDone());
        assertNull(fcb.get());
        assertEquals(10, endPoint.fill(buffer));
        assertEquals("test input", buffer.getString(buffer.readPosition(), UTF_8));

        fcb = new FutureCallback();
        endPoint.fillInterested(fcb);
        Thread.sleep(100);
        assertFalse(fcb.isDone());
        assertEquals(0, endPoint.fill(buffer));

        endPoint.addInput(" more");
        fcb.get(100, TimeUnit.MILLISECONDS);
        assertTrue(fcb.isDone());
        assertNull(fcb.get());
        assertEquals(5, endPoint.fill(buffer));
        assertEquals("test input more", buffer.getString(buffer.readPosition(), UTF_8));

        fcb = new FutureCallback();
        endPoint.fillInterested(fcb);
        Thread.sleep(100);
        assertFalse(fcb.isDone());
        assertEquals(0, endPoint.fill(buffer));

        endPoint.addInput((RetainableByteBuffer)null);
        assertTrue(fcb.isDone());
        assertNull(fcb.get());
        assertEquals(-1, endPoint.fill(buffer));

        fcb = new FutureCallback();
        endPoint.fillInterested(fcb);
        fcb.get(100, TimeUnit.MILLISECONDS);
        assertTrue(fcb.isDone());
        assertNull(fcb.get());
        assertEquals(-1, endPoint.fill(buffer));

        endPoint.close();

        FutureCallback cb = new FutureCallback();
        endPoint.fillInterested(cb);

        ExecutionException failure = assertThrows(ExecutionException.class, () -> cb.get(100, TimeUnit.MILLISECONDS));
        assertThat(failure.toString(), containsString("Closed"));
    }

    @Test
    public void testWrite() throws Exception
    {
        try (ByteArrayEndPoint endPoint = new ByteArrayEndPoint(_scheduler, 5000, (byte[])null, 10))
        {
            RetainableByteBuffer data = RetainableByteBuffer.wrap("Data.", UTF_8);
            RetainableByteBuffer more = RetainableByteBuffer.wrap(" Some more.", UTF_8);

            FutureCallback fcb = new FutureCallback();
            endPoint.write(data, fcb);
            assertTrue(fcb.isDone());
            assertNull(fcb.get());
            assertEquals("Data.", endPoint.getOutputString(UTF_8));

            fcb = new FutureCallback();
            endPoint.write(more, fcb);
            assertFalse(fcb.isDone());

            assertEquals("Data. Some", endPoint.getOutputString(UTF_8));
            assertEquals("Data. Some", endPoint.takeOutputString());

            assertTrue(fcb.isDone());
            assertNull(fcb.get());
            assertEquals(" more.", endPoint.getOutputString(UTF_8));
        }
    }

    @Test
    public void testIdle() throws Exception
    {
        long idleTimeout = 1500;
        long halfIdleTimeout = idleTimeout / 2;
        long oneAndHalfIdleTimeout = idleTimeout + halfIdleTimeout;

        try (ByteArrayEndPoint endPoint = new ByteArrayEndPoint(_scheduler, idleTimeout, null, 5, false))
        {
            endPoint.addInput("test");

            assertTrue(endPoint.isOpen());
            Thread.sleep(oneAndHalfIdleTimeout);
            assertFalse(endPoint.isOpen());
        }
    }
}
