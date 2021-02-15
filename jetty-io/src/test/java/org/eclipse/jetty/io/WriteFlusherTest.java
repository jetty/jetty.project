//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertTrue(callback.isDone());
        assertFalse(incompleteFlush.get());
        assertEquals("How now brown cow!", endPoint.takeOutputString());
        assertTrue(flusher.isIdle());
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

        assertTrue(callback.isDone());
        assertFalse(incompleteFlush.get());

        ExecutionException e = assertThrows(ExecutionException.class, () ->
        {
            callback.get();
        });
        assertThat(e.getCause(), instanceOf(IOException.class));
        assertThat(e.getCause().getMessage(), containsString("CLOSED"));

        assertEquals("", endPoint.takeOutputString());
        assertTrue(flusher.isFailed());
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

        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());

        assertTrue(incompleteFlush.get());

        assertThrows(TimeoutException.class, () ->
        {
            callback.get(100, TimeUnit.MILLISECONDS);
        });

        incompleteFlush.set(false);

        assertEquals("How now br", endPoint.takeOutputString());

        flusher.completeWrite();

        assertTrue(callback.isDone());
        assertEquals("own cow!", endPoint.takeOutputString());
        assertFalse(incompleteFlush.get());
        assertTrue(flusher.isIdle());
    }

    @Test
    public void testCallbackThrows() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 100);

        AtomicBoolean incompleteFlush = new AtomicBoolean(false);
        WriteFlusher flusher = new WriteFlusher(endPoint)
        {
            @Override
            protected void onIncompleteFlush()
            {
                incompleteFlush.set(true);
            }
        };

        FutureCallback callback = new FutureCallback()
        {
            @Override
            public void succeeded()
            {
                super.succeeded();
                throw new IllegalStateException();
            }
        };

        try (StacklessLogging stacklessLogging = new StacklessLogging(WriteFlusher.class))
        {
            flusher.write(callback, BufferUtil.toBuffer("How now brown cow!"));
            callback.get(100, TimeUnit.MILLISECONDS);
        }

        assertEquals("How now brown cow!", endPoint.takeOutputString());
        assertTrue(callback.isDone());
        assertFalse(incompleteFlush.get());
        assertTrue(flusher.isIdle());
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

        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());

        assertTrue(incompleteFlush.get());
        incompleteFlush.set(false);

        assertEquals("How now br", endPoint.takeOutputString());

        endPoint.close();
        flusher.completeWrite();

        assertTrue(callback.isDone());
        assertFalse(incompleteFlush.get());

        ExecutionException e = assertThrows(ExecutionException.class, () -> callback.get());
        assertThat(e.getCause(), instanceOf(IOException.class));
        assertThat(e.getCause().getMessage(), containsString("CLOSED"));

        assertEquals("", endPoint.takeOutputString());
        assertTrue(flusher.isFailed());
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

        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());

        assertTrue(incompleteFlush.get());
        incompleteFlush.set(false);

        assertEquals("How now br", endPoint.takeOutputString());

        String reason = "Failure";
        flusher.onFail(new IOException(reason));
        flusher.completeWrite();

        assertTrue(callback.isDone());
        assertFalse(incompleteFlush.get());

        ExecutionException e = assertThrows(ExecutionException.class, () -> callback.get());
        assertThat(e.getCause(), instanceOf(IOException.class));
        assertThat(e.getCause().getMessage(), containsString(reason));

        assertEquals("", endPoint.takeOutputString());
        assertTrue(flusher.isFailed());
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
                    assertEquals("How Now Brown Cow. The quick brown fox jumped over the lazy dog!", flushers[i].getContent());
                    completed++;
                }
                catch (ExecutionException x)
                {
                    assertEquals(reason, x.getCause().getMessage());
                    failed++;
                }
            }
            assertThat(completed, Matchers.greaterThan(0));
            assertThat(failed, Matchers.greaterThan(0));
            assertEquals(flushers.length, completed + failed);
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
        assertTrue(incompleteFlush.get());
        assertFalse(buffer1.hasRemaining());

        // Reuse buffer1
        buffer1.clear();
        Arrays.fill(chunk1, (byte)3);
        int remaining1 = buffer1.remaining();

        // Complete the write
        endPoint.takeOutput();
        flusher.completeWrite();

        // Make sure buffer1 is unchanged
        assertEquals(remaining1, buffer1.remaining());
    }

    @Test
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
        assertTrue(flushLatch.await(1, TimeUnit.SECONDS));

        assertThrows(WritePendingException.class, () ->
        {
            // The second write throws WritePendingException.
            flusher.write(Callback.NOOP, BufferUtil.toBuffer("bar"));
        });
    }

    @Test
    public void testConcurrentWriteAndOnFail() throws Exception
    {
        assertThrows(ExecutionException.class, () ->
        {
            ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 16);

            WriteFlusher flusher = new WriteFlusher(endPoint)
            {
                @Override
                protected ByteBuffer[] flush(ByteBuffer[] buffers)
                    throws IOException
                {
                    ByteBuffer[] result = super.flush(buffers);
                    boolean notified = onFail(new Throwable());
                    assertTrue(notified);
                    return result;
                }

                @Override
                protected void onIncompleteFlush()
                {
                }
            };

            FutureCallback callback = new FutureCallback();
            flusher.write(callback, BufferUtil.toBuffer("foo"));

            assertTrue(flusher.isFailed());

            callback.get(1, TimeUnit.SECONDS);
        });
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
            assertEquals(reason, x.getCause().getMessage());
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
}
