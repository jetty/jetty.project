//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class WriteFlusherTest
{
    @Test
    public void testCompleteNoBlocking() throws Exception
    {
        testCompleteWrite(false);
    }

    @Test
    public void testIgnorePreviousFailures() throws Exception
    {
        testCompleteWrite(true);
    }

    private void testCompleteWrite(boolean failBefore) throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 16);
        endPoint.setGrowOutput(true);

        AtomicBoolean incompleteFlush = new AtomicBoolean();
        WriteFlusher flusher = new WriteFlusher(endPoint)
        {
            @Override
            protected void onIncompleteFlush()
            {
                incompleteFlush.set(true);
            }
        };

        if (failBefore)
            flusher.onFail(new IOException("Ignored because no operation in progress"));

        FutureCallback callback = new FutureCallback();
        flusher.write(callback, BufferUtil.toBuffer("How "), BufferUtil.toBuffer("now "), BufferUtil.toBuffer("brown "), BufferUtil.toBuffer("cow!"));

        Assert.assertTrue(callback.isDone());
        Assert.assertFalse(incompleteFlush.get());
        Assert.assertEquals("How now brown cow!", endPoint.takeOutputString());
        Assert.assertTrue(flusher.isIdle());
    }

    @Test
    public void testClosedNoBlocking() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 16);
        endPoint.close();

        AtomicBoolean incompleteFlush = new AtomicBoolean();
        WriteFlusher flusher = new WriteFlusher(endPoint)
        {
            @Override
            protected void onIncompleteFlush()
            {
                incompleteFlush.set(true);
            }
        };

        FutureCallback callback = new FutureCallback();
        flusher.write(callback, BufferUtil.toBuffer("foo"));

        Assert.assertTrue(callback.isDone());
        Assert.assertFalse(incompleteFlush.get());

        try
        {
            callback.get();
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof IOException);
            Assert.assertThat(cause.getMessage(), Matchers.containsString("CLOSED"));
        }
        Assert.assertEquals("", endPoint.takeOutputString());
        Assert.assertTrue(flusher.isIdle());
    }

    @Test
    public void testCompleteBlocking() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 10);

        AtomicBoolean incompleteFlush = new AtomicBoolean();
        WriteFlusher flusher = new WriteFlusher(endPoint)
        {
            @Override
            protected void onIncompleteFlush()
            {
                incompleteFlush.set(true);
            }
        };

        FutureCallback callback = new FutureCallback();
        flusher.write(callback, BufferUtil.toBuffer("How now brown cow!"));

        Assert.assertFalse(callback.isDone());
        Assert.assertFalse(callback.isCancelled());

        Assert.assertTrue(incompleteFlush.get());

        try
        {
            callback.get(100, TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch (TimeoutException x)
        {
            incompleteFlush.set(false);
        }

        Assert.assertEquals("How now br", endPoint.takeOutputString());

        flusher.completeWrite();

        Assert.assertTrue(callback.isDone());
        Assert.assertEquals("own cow!", endPoint.takeOutputString());
        Assert.assertFalse(incompleteFlush.get());
        Assert.assertTrue(flusher.isIdle());
    }

    @Test
    public void testCloseWhileBlocking() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 10);

        AtomicBoolean incompleteFlush = new AtomicBoolean();
        WriteFlusher flusher = new WriteFlusher(endPoint)
        {
            @Override
            protected void onIncompleteFlush()
            {
                incompleteFlush.set(true);
            }
        };

        FutureCallback callback = new FutureCallback();
        flusher.write(callback, BufferUtil.toBuffer("How now brown cow!"));

        Assert.assertFalse(callback.isDone());
        Assert.assertFalse(callback.isCancelled());

        Assert.assertTrue(incompleteFlush.get());
        incompleteFlush.set(false);

        Assert.assertEquals("How now br", endPoint.takeOutputString());

        endPoint.close();
        flusher.completeWrite();

        Assert.assertTrue(callback.isDone());
        Assert.assertFalse(incompleteFlush.get());

        try
        {
            callback.get();
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof IOException);
            Assert.assertThat(cause.getMessage(), Matchers.containsString("CLOSED"));
        }
        Assert.assertEquals("", endPoint.takeOutputString());
        Assert.assertTrue(flusher.isIdle());
    }

    @Test
    public void testFailWhileBlocking() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 10);

        AtomicBoolean incompleteFlush = new AtomicBoolean();
        WriteFlusher flusher = new WriteFlusher(endPoint)
        {
            @Override
            protected void onIncompleteFlush()
            {
                incompleteFlush.set(true);
            }
        };

        FutureCallback callback = new FutureCallback();
        flusher.write(callback, BufferUtil.toBuffer("How now brown cow!"));

        Assert.assertFalse(callback.isDone());
        Assert.assertFalse(callback.isCancelled());

        Assert.assertTrue(incompleteFlush.get());
        incompleteFlush.set(false);

        Assert.assertEquals("How now br", endPoint.takeOutputString());

        String reason = "Failure";
        flusher.onFail(new IOException(reason));
        flusher.completeWrite();

        Assert.assertTrue(callback.isDone());
        Assert.assertFalse(incompleteFlush.get());

        try
        {
            callback.get();
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof IOException);
            Assert.assertEquals(reason, cause.getMessage());
        }
        Assert.assertEquals("", endPoint.takeOutputString());
        Assert.assertTrue(flusher.isIdle());
    }

    @Test
    public void testConcurrent() throws Exception
    {
        Random random = new Random();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(100);
        try
        {
            String reason = "THE_CAUSE";
            ConcurrentWriteFlusher[] flushers = new ConcurrentWriteFlusher[50000];
            FutureCallback[] futures = new FutureCallback[flushers.length];
            for (int i = 0; i < flushers.length; ++i)
            {
                int size = 5 + random.nextInt(15);
                ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], size);
                ConcurrentWriteFlusher flusher = new ConcurrentWriteFlusher(endPoint, scheduler, random);
                flushers[i] = flusher;
                FutureCallback callback = new FutureCallback();
                futures[i] = callback;
                scheduler.schedule(() -> flusher.onFail(new Throwable(reason)), random.nextInt(75) + 1, TimeUnit.MILLISECONDS);
                flusher.write(callback, BufferUtil.toBuffer("How Now Brown Cow."), BufferUtil.toBuffer(" The quick brown fox jumped over the lazy dog!"));
            }

            int completed = 0;
            int failed = 0;
            for (int i = 0; i < flushers.length; ++i)
            {
                try
                {
                    futures[i].get(15, TimeUnit.SECONDS);
                    Assert.assertEquals("How Now Brown Cow. The quick brown fox jumped over the lazy dog!", flushers[i].getContent());
                    completed++;
                }
                catch (ExecutionException x)
                {
                    Assert.assertEquals(reason, x.getCause().getMessage());
                    failed++;
                }
            }
            Assert.assertThat(completed, Matchers.greaterThan(0));
            Assert.assertThat(failed, Matchers.greaterThan(0));
            Assert.assertEquals(flushers.length, completed + failed);
        }
        finally
        {
            scheduler.shutdown();
        }
    }

    @Test
    public void testPendingWriteDoesNotStoreConsumedBuffers() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 10);

        int toWrite = endPoint.getOutput().capacity();
        byte[] chunk1 = new byte[toWrite / 2];
        Arrays.fill(chunk1, (byte)1);
        ByteBuffer buffer1 = ByteBuffer.wrap(chunk1);
        byte[] chunk2 = new byte[toWrite];
        Arrays.fill(chunk1, (byte)2);
        ByteBuffer buffer2 = ByteBuffer.wrap(chunk2);

        AtomicBoolean incompleteFlush = new AtomicBoolean();
        WriteFlusher flusher = new WriteFlusher(endPoint)
        {
            @Override
            protected void onIncompleteFlush()
            {
                incompleteFlush.set(true);
            }
        };


        flusher.write(Callback.NOOP, buffer1, buffer2);
        Assert.assertTrue(incompleteFlush.get());
        Assert.assertFalse(buffer1.hasRemaining());

        // Reuse buffer1
        buffer1.clear();
        Arrays.fill(chunk1, (byte)3);
        int remaining1 = buffer1.remaining();

        // Complete the write
        endPoint.takeOutput();
        flusher.completeWrite();

        // Make sure buffer1 is unchanged
        Assert.assertEquals(remaining1, buffer1.remaining());
    }

    @Test(expected = WritePendingException.class)
    public void testConcurrentWrites() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 16);

        CountDownLatch flushLatch = new CountDownLatch(1);
        WriteFlusher flusher = new WriteFlusher(endPoint)
        {
            @Override
            protected ByteBuffer[] flush(ByteBuffer[] buffers) throws IOException
            {
                try
                {
                    flushLatch.countDown();
                    Thread.sleep(2000);
                    return super.flush(buffers);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }

            @Override
            protected void onIncompleteFlush()
            {
            }
        };

        // Two concurrent writes.
        new Thread(() -> flusher.write(Callback.NOOP, BufferUtil.toBuffer("foo"))).start();
        Assert.assertTrue(flushLatch.await(1, TimeUnit.SECONDS));
        // The second write throws WritePendingException.
        flusher.write(Callback.NOOP, BufferUtil.toBuffer("bar"));
    }

    @Test
    public void testConcurrentWriteAndOnFail() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 16);

        WriteFlusher flusher = new WriteFlusher(endPoint)
        {
            @Override
            protected ByteBuffer[] flush(ByteBuffer[] buffers) throws IOException
            {
                ByteBuffer[] result = super.flush(buffers);
                boolean notified = onFail(new Throwable());
                Assert.assertFalse(notified);
                return result;
            }

            @Override
            protected void onIncompleteFlush()
            {
            }
        };

        FutureCallback callback = new FutureCallback();
        flusher.write(callback, BufferUtil.toBuffer("foo"));

        // Callback must be successfully completed.
        callback.get(1, TimeUnit.SECONDS);
        // Flusher must be idle - not failed - since the write succeeded.
        Assert.assertTrue(flusher.isIdle());
    }

    @Test
    public void testConcurrentIncompleteFlushAndOnFail() throws Exception
    {
        int capacity = 8;
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], capacity);
        String reason = "the_reason";

        WriteFlusher flusher = new WriteFlusher(endPoint)
        {
            @Override
            protected void onIncompleteFlush()
            {
                onFail(new Throwable(reason));
            }
        };

        FutureCallback callback = new FutureCallback();
        byte[] content = new byte[capacity * 2];
        flusher.write(callback, BufferUtil.toBuffer(content));

        try
        {
            // Callback must be failed.
            callback.get(1, TimeUnit.SECONDS);
        }
        catch (ExecutionException x)
        {
            Assert.assertEquals(reason, x.getCause().getMessage());
        }
    }

    private static class ConcurrentWriteFlusher extends WriteFlusher implements Runnable
    {
        private final ByteArrayEndPoint endPoint;
        private final ScheduledExecutorService scheduler;
        private final Random random;
        private String content = "";

        private ConcurrentWriteFlusher(ByteArrayEndPoint endPoint, ScheduledThreadPoolExecutor scheduler, Random random)
        {
            super(endPoint);
            this.endPoint = endPoint;
            this.scheduler = scheduler;
            this.random = random;
        }

        @Override
        protected void onIncompleteFlush()
        {
            scheduler.schedule(this, 1 + random.nextInt(9), TimeUnit.MILLISECONDS);
        }

        @Override
        public void run()
        {
            content += endPoint.takeOutputString();
            completeWrite();
        }

        private String getContent()
        {
            content += endPoint.takeOutputString();
            return content;
        }
    }
    
    @Test
    public void testOverMinDataRate() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 5);
        endPoint.setIdleTimeout(1000);

        AtomicBoolean incompleteFlush = new AtomicBoolean();
        WriteFlusher flusher = new WriteFlusher(endPoint)
        {
            @Override
            protected void onIncompleteFlush()
            {
                incompleteFlush.set(true);
            }
        };
        
        flusher.setMinDataRate(10);

        for (int i=5;i-->0;)
        {
            FutureCallback callback = new FutureCallback();
            flusher.write(callback, BufferUtil.toBuffer("0123456789ABCDE"));
         
            assertTrue(incompleteFlush.get());
            assertThat(endPoint.takeOutputString(),Matchers.is("01234"));
            
            Thread.sleep(500);
            incompleteFlush.set(false);
            flusher.completeWrite();
            assertTrue(incompleteFlush.get());
            assertThat(endPoint.takeOutputString(),Matchers.is("56789"));

            Thread.sleep(500);
            incompleteFlush.set(false);
            flusher.completeWrite();
            assertFalse(incompleteFlush.get());
            assertThat(endPoint.takeOutputString(),Matchers.is("ABCDE"));
                        
            callback.get();
        }
    }
    

    @Test
    public void testUnderMinDataRate() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 2);
        endPoint.setIdleTimeout(1000);

        AtomicBoolean incompleteFlush = new AtomicBoolean();
        WriteFlusher flusher = new WriteFlusher(endPoint)
        {
            @Override
            protected void onIncompleteFlush()
            {
                incompleteFlush.set(true);
            }
        };
        
        flusher.setMinDataRate(10);

        try
        {
            for (int i=5;i-->0;)
            {
                FutureCallback callback = new FutureCallback();
                flusher.write(callback, BufferUtil.toBuffer("012345"));

                assertTrue(incompleteFlush.get());
                assertThat(endPoint.takeOutputString(),Matchers.is("01"));

                Thread.sleep(500);
                incompleteFlush.set(false);
                flusher.completeWrite();
                if (incompleteFlush.get())
                {
                    assertThat(endPoint.takeOutputString(),Matchers.is("23"));

                    Thread.sleep(500);
                    incompleteFlush.set(false);
                    flusher.completeWrite();
                    if (incompleteFlush.get())
                    {
                        assertThat(endPoint.takeOutputString(),Matchers.is("45"));
                    }
                }

                callback.get();
            }
            Assert.fail();
        }
        catch(ExecutionException ee)
        {
            assertTrue(ee.getCause() instanceof IOException);
            assertThat(ee.getCause().getMessage(),Matchers.containsString("insufficient data rate"));
        }
    }
}
