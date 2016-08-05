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

package org.eclipse.jetty.io;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AdvancedRunner.class)
public class ByteArrayEndPointTest
{
    private Scheduler _scheduler;

    @Before
    public void before() throws Exception
    {
        _scheduler = new TimerScheduler();
        _scheduler.start();
    }

    @After
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

        assertEquals(10,endp.fill(buffer));
        assertEquals("test input",BufferUtil.toString(buffer));

        assertEquals(0,endp.fill(buffer));

        endp.addInput(" more");
        assertEquals(5,endp.fill(buffer));
        assertEquals("test input more",BufferUtil.toString(buffer));

        assertEquals(0,endp.fill(buffer));

        endp.addInput((ByteBuffer)null);

        assertEquals(-1,endp.fill(buffer));

        endp.close();

        try
        {
            endp.fill(buffer);
            fail();
        }
        catch(IOException e)
        {
            assertThat(e.getMessage(),containsString("CLOSED"));
        }

        endp.reset();
        endp.addInput("and more");
        buffer = BufferUtil.allocate(4);

        assertEquals(4,endp.fill(buffer));
        assertEquals("and ",BufferUtil.toString(buffer));
        assertEquals(0,endp.fill(buffer));
        BufferUtil.clear(buffer);
        assertEquals(4,endp.fill(buffer));
        assertEquals("more",BufferUtil.toString(buffer));
    }

    @Test
    public void testGrowingFlush() throws Exception
    {
        ByteArrayEndPoint endp = new ByteArrayEndPoint((byte[])null,15);
        endp.setGrowOutput(true);

        assertEquals(true,endp.flush(BufferUtil.toBuffer("some output")));
        assertEquals("some output",endp.getOutputString());

        assertEquals(true,endp.flush(BufferUtil.toBuffer(" some more")));
        assertEquals("some output some more",endp.getOutputString());

        assertEquals(true,endp.flush());
        assertEquals("some output some more",endp.getOutputString());

        assertEquals(true,endp.flush(BufferUtil.EMPTY_BUFFER));
        assertEquals("some output some more",endp.getOutputString());

        assertEquals(true,endp.flush(BufferUtil.EMPTY_BUFFER,BufferUtil.toBuffer(" and"),BufferUtil.toBuffer(" more")));
        assertEquals("some output some more and more",endp.getOutputString());
        endp.close();
    }

    @Test
    public void testFlush() throws Exception
    {
        ByteArrayEndPoint endp = new ByteArrayEndPoint((byte[])null,15);
        endp.setGrowOutput(false);
        endp.setOutput(BufferUtil.allocate(10));

        ByteBuffer data = BufferUtil.toBuffer("Some more data.");
        assertEquals(false,endp.flush(data));
        assertEquals("Some more ",endp.getOutputString());
        assertEquals("data.",BufferUtil.toString(data));

        assertEquals("Some more ",endp.takeOutputString());

        assertEquals(true,endp.flush(data));
        assertEquals("data.",BufferUtil.toString(endp.takeOutput()));
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
        fcb.get(100,TimeUnit.MILLISECONDS);
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
        fcb.get(1000,TimeUnit.MILLISECONDS);
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
        fcb.get(1000,TimeUnit.MILLISECONDS);
        assertTrue(fcb.isDone());
        assertEquals(null, fcb.get());
        assertEquals(-1, endp.fill(buffer));

        endp.close();

        fcb = new FutureCallback();
        endp.fillInterested(fcb);
        try
        {
            fcb.get(1000,TimeUnit.MILLISECONDS);
            fail();
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
        endp.write( fcb, data);
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

    @Slow
    @Test
    public void testIdle() throws Exception
    {
        long idleTimeout = 1500;
        ByteArrayEndPoint endp = new ByteArrayEndPoint(_scheduler, idleTimeout);
        endp.addInput("test");
        endp.setGrowOutput(false);
        endp.setOutput(BufferUtil.allocate(5));

        // no idle check
        assertTrue(endp.isOpen());
        Thread.sleep(idleTimeout * 2);
        assertTrue(endp.isOpen());

        // normal read
        ByteBuffer buffer = BufferUtil.allocate(1024);
        FutureCallback fcb = new FutureCallback();

        endp.fillInterested(fcb);
        fcb.get(idleTimeout,TimeUnit.MILLISECONDS);
        assertTrue(fcb.isDone());
        assertEquals(null, fcb.get());
        assertEquals(4, endp.fill(buffer));
        assertEquals("test", BufferUtil.toString(buffer));

        // read timeout
        fcb = new FutureCallback();
        endp.fillInterested(fcb);
        long start = System.currentTimeMillis();
        try
        {
            fcb.get();
            fail();
        }
        catch (ExecutionException t)
        {
            assertThat(t.getCause(), instanceOf(TimeoutException.class));
        }
        assertThat(System.currentTimeMillis() - start, greaterThan(idleTimeout / 2));
        assertThat("Endpoint open", endp.isOpen(), is(true));

        // We need to delay the write timeout test below from the read timeout test above.
        // The reason is that the scheduler thread that fails the endPoint WriteFlusher
        // because of the read timeout above runs concurrently with the write below, and
        // if it runs just after the write below, the test fails because the write callback
        // below fails immediately rather than after the idle timeout.
        Thread.sleep(idleTimeout / 2);

        // write timeout
        fcb = new FutureCallback();
        endp.write(fcb, BufferUtil.toBuffer("This is too long"));
        start = System.currentTimeMillis();
        try
        {
            fcb.get();
            fail();
        }
        catch (ExecutionException t)
        {
            assertThat(t.getCause(), instanceOf(TimeoutException.class));
        }
        assertThat(System.currentTimeMillis() - start, greaterThan(idleTimeout / 2));
        assertThat("Endpoint open", endp.isOpen(), is(true));

        endp.fillInterested(new Closer(endp));
        
        // Still no idle close (wait half the time)
        Thread.sleep(idleTimeout / 2);
        assertThat("Endpoint open", endp.isOpen(), is(true));

        // shutdown out
        endp.shutdownOutput();

        // idle close (wait double the time)
        Thread.sleep(idleTimeout * 2);
        assertThat("Endpoint open", endp.isOpen(), is(false));
    }
}
