//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ByteArrayEndPointTest
{
    private Scheduler _scheduler;

    @BeforeEach
    public void before() throws Exception
    {
        _scheduler = new TimerScheduler();
        _scheduler.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _scheduler.stop();
    }

    @Test
    public void testFill() throws Exception
    {
        ByteArrayEndPoint endp = new ByteArrayEndPoint();
        endp.addInput("test input");

        ByteBuffer buffer = BufferUtil.allocate(1024);

        assertEquals(10, endp.fill(buffer));
        assertEquals("test input", BufferUtil.toString(buffer));

        assertEquals(0, endp.fill(buffer));

        endp.addInput(" more");
        assertEquals(5, endp.fill(buffer));
        assertEquals("test input more", BufferUtil.toString(buffer));

        assertEquals(0, endp.fill(buffer));

        endp.addInput((ByteBuffer)null);

        assertEquals(-1, endp.fill(buffer));

        endp.close();

        try
        {
            endp.fill(buffer);
            fail("Expected IOException");
        }
        catch (IOException e)
        {
            assertThat(e.getMessage(), containsString("CLOSED"));
        }

        endp.reset();
        endp.addInput("and more");
        buffer = BufferUtil.allocate(4);

        assertEquals(4, endp.fill(buffer));
        assertEquals("and ", BufferUtil.toString(buffer));
        assertEquals(0, endp.fill(buffer));
        BufferUtil.clear(buffer);
        assertEquals(4, endp.fill(buffer));
        assertEquals("more", BufferUtil.toString(buffer));
    }

    @Test
    public void testGrowingFlush() throws Exception
    {
        ByteArrayEndPoint endp = new ByteArrayEndPoint((byte[])null, 15);
        endp.setGrowOutput(true);

        assertEquals(true, endp.flush(BufferUtil.toBuffer("some output")));
        assertEquals("some output", endp.getOutputString());

        assertEquals(true, endp.flush(BufferUtil.toBuffer(" some more")));
        assertEquals("some output some more", endp.getOutputString());

        assertEquals(true, endp.flush());
        assertEquals("some output some more", endp.getOutputString());

        assertEquals(true, endp.flush(BufferUtil.EMPTY_BUFFER));
        assertEquals("some output some more", endp.getOutputString());

        assertEquals(true, endp.flush(BufferUtil.EMPTY_BUFFER, BufferUtil.toBuffer(" and"), BufferUtil.toBuffer(" more")));
        assertEquals("some output some more and more", endp.getOutputString());
        endp.close();
    }

    @Test
    public void testFlush() throws Exception
    {
        ByteArrayEndPoint endp = new ByteArrayEndPoint((byte[])null, 15);
        endp.setGrowOutput(false);
        endp.setOutput(BufferUtil.allocate(10));

        ByteBuffer data = BufferUtil.toBuffer("Some more data.");
        assertEquals(false, endp.flush(data));
        assertEquals("Some more ", endp.getOutputString());
        assertEquals("data.", BufferUtil.toString(data));

        assertEquals("Some more ", endp.takeOutputString());

        assertEquals(true, endp.flush(data));
        assertEquals("data.", BufferUtil.toString(endp.takeOutput()));
        endp.close();
    }

    @Test
    public void testReadable() throws Exception
    {
        ByteArrayEndPoint endp = new ByteArrayEndPoint(_scheduler, 5000);
        endp.addInput("test input");

        ByteBuffer buffer = BufferUtil.allocate(1024);
        FutureCallback fcb = new FutureCallback();

        endp.fillInterested(fcb);
        fcb.get(100, TimeUnit.MILLISECONDS);
        assertTrue(fcb.isDone());
        assertEquals(null, fcb.get());
        assertEquals(10, endp.fill(buffer));
        assertEquals("test input", BufferUtil.toString(buffer));

        fcb = new FutureCallback();
        endp.fillInterested(fcb);
        Thread.sleep(100);
        assertFalse(fcb.isDone());
        assertEquals(0, endp.fill(buffer));

        endp.addInput(" more");
        fcb.get(1000, TimeUnit.MILLISECONDS);
        assertTrue(fcb.isDone());
        assertEquals(null, fcb.get());
        assertEquals(5, endp.fill(buffer));
        assertEquals("test input more", BufferUtil.toString(buffer));

        fcb = new FutureCallback();
        endp.fillInterested(fcb);
        Thread.sleep(100);
        assertFalse(fcb.isDone());
        assertEquals(0, endp.fill(buffer));

        endp.addInput((ByteBuffer)null);
        assertTrue(fcb.isDone());
        assertEquals(null, fcb.get());
        assertEquals(-1, endp.fill(buffer));

        fcb = new FutureCallback();
        endp.fillInterested(fcb);
        fcb.get(1000, TimeUnit.MILLISECONDS);
        assertTrue(fcb.isDone());
        assertEquals(null, fcb.get());
        assertEquals(-1, endp.fill(buffer));

        endp.close();

        fcb = new FutureCallback();
        endp.fillInterested(fcb);

        try
        {
            fcb.get(1000, TimeUnit.MILLISECONDS);
            fail("Expected ExecutionException");
        }
        catch (ExecutionException e)
        {
            assertThat(e.toString(), containsString("Closed"));
        }
    }

    @Test
    public void testWrite() throws Exception
    {
        ByteArrayEndPoint endp = new ByteArrayEndPoint(_scheduler, 5000, (byte[])null, 15);
        endp.setGrowOutput(false);
        endp.setOutput(BufferUtil.allocate(10));

        ByteBuffer data = BufferUtil.toBuffer("Data.");
        ByteBuffer more = BufferUtil.toBuffer(" Some more.");

        FutureCallback fcb = new FutureCallback();
        endp.write(fcb, data);
        assertTrue(fcb.isDone());
        assertEquals(null, fcb.get());
        assertEquals("Data.", endp.getOutputString());

        fcb = new FutureCallback();
        endp.write(fcb, more);
        assertFalse(fcb.isDone());

        assertEquals("Data. Some", endp.getOutputString());
        assertEquals("Data. Some", endp.takeOutputString());

        assertTrue(fcb.isDone());
        assertEquals(null, fcb.get());
        assertEquals(" more.", endp.getOutputString());
        endp.close();
    }

    /**
     * Simulate AbstractConnection.ReadCallback.failed()
     */
    public static class Closer extends FutureCallback
    {
        private EndPoint endp;

        public Closer(EndPoint endp)
        {
            this.endp = endp;
        }

        @Override
        public void failed(Throwable cause)
        {
            endp.close();
            super.failed(cause);
        }
    }

    @Test
    public void testIdle() throws Exception
    {
        long idleTimeout = 1500;
        long halfIdleTimeout = idleTimeout / 2;
        long oneAndHalfIdleTimeout = idleTimeout + halfIdleTimeout;

        ByteArrayEndPoint endp = new ByteArrayEndPoint(_scheduler, idleTimeout);
        endp.setGrowOutput(false);
        endp.addInput("test");
        endp.setOutput(BufferUtil.allocate(5));

        assertTrue(endp.isOpen());
        Thread.sleep(oneAndHalfIdleTimeout);
        // Still open because it has not been oshut or closed explicitly
        // and there are no callbacks, so idle timeout is ignored.
        assertTrue(endp.isOpen());

        // Normal read is immediate, since there is data to read.
        ByteBuffer buffer = BufferUtil.allocate(1024);
        FutureCallback fcb = new FutureCallback();
        endp.fillInterested(fcb);
        fcb.get(idleTimeout, TimeUnit.MILLISECONDS);
        assertTrue(fcb.isDone());
        assertEquals(4, endp.fill(buffer));
        assertEquals("test", BufferUtil.toString(buffer));

        // Wait for a read timeout.
        long start = NanoTime.now();
        fcb = new FutureCallback();
        endp.fillInterested(fcb);
        try
        {
            fcb.get();
            fail("Expected ExecutionException");
        }
        catch (ExecutionException t)
        {
            assertThat(t.getCause(), instanceOf(TimeoutException.class));
        }
        assertThat(NanoTime.millisSince(start), greaterThan(halfIdleTimeout));
        assertThat("Endpoint open", endp.isOpen(), is(true));
    }
}
