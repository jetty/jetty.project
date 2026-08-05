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
import java.io.InterruptedIOException;
import java.net.SocketAddress;
import java.nio.channels.WritePendingException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WriteFlusherTest
{
    @Test
    public void testCancelWriteBeforeWrite()
    {
        try (ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 16, false))
        {
            WriteFlusher flusher = endPoint.getWriteFlusher();

            assertThat(flusher.isFailed(), is(false));
            Callback callback = flusher.cancelWrite(new ArithmeticException());
            assertNull(callback);
            assertThat(flusher.isFailed(), is(true));

            AtomicReference<Throwable> failureRef = new AtomicReference<>();
            endPoint.write(RetainableByteBuffer.allocate(32, false), Callback.from(() -> failureRef.set(new AssertionError("expected callback to be failed")), failureRef::set));
            assertThat(failureRef.get(), instanceOf(ArithmeticException.class));
        }
    }

    @Test
    public void testCancelWriteDuringPendingWrite()
    {
        try (ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 16, false))
        {
            WriteFlusher flusher = endPoint.getWriteFlusher();

            AtomicReference<Throwable> failureRef = new AtomicReference<>();
            Callback writeCallback = Callback.from(() -> failureRef.set(new AssertionError("expected callback to be failed")), failureRef::set);
            RetainableByteBuffer buffer = RetainableByteBuffer.allocate(32, false);
            endPoint.write(buffer, writeCallback);
            assertThat(failureRef.get(), nullValue());
            assertThat(buffer.remaining(), is(16L));
            assertThat(flusher.isPending(), is(true));

            Callback cancelCallback = flusher.cancelWrite(new ArithmeticException());
            assertThat(failureRef.get(), nullValue());
            assertThat(flusher.isFailed(), is(true));
            cancelCallback.failed(new IllegalCallerException());
            assertThat(failureRef.get(), instanceOf(IllegalCallerException.class));

            failureRef.set(null);
            endPoint.write(buffer, writeCallback);
            assertThat(failureRef.get(), instanceOf(ArithmeticException.class));
        }
    }

    @Test
    public void testCompleteNoBlocking()
    {
        testCompleteWrite(false);
    }

    @Test
    public void testIgnorePreviousFailures()
    {
        testCompleteWrite(true);
    }

    private void testCompleteWrite(boolean failBefore)
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 16, true);

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
        flusher.write(RetainableByteBuffer.wrap(
            RetainableByteBuffer.wrap("How ", UTF_8),
            RetainableByteBuffer.wrap("now ", UTF_8),
            RetainableByteBuffer.wrap("brown ", UTF_8),
            RetainableByteBuffer.wrap("cow!", UTF_8)
        ), callback);

        assertTrue(callback.isDone());
        assertFalse(incompleteFlush.get());
        assertEquals("How now brown cow!", endPoint.takeOutputString());
        assertTrue(flusher.isIdle());
    }

    @Test
    public void testClosedNoBlocking()
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
        flusher.write(RetainableByteBuffer.wrap(BufferUtil.toBuffer("foo")), callback);

        assertTrue(callback.isDone());
        assertFalse(incompleteFlush.get());

        ExecutionException e = assertThrows(ExecutionException.class, callback::get);
        assertThat(e.getCause(), instanceOf(IOException.class));
        assertThat(e.getCause().getMessage(), containsString("CLOSED"));

        assertThrows(Exception.class, endPoint::takeOutputString);
        assertTrue(flusher.isFailed());
    }

    @Test
    public void testCompleteBlocking()
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
        // Write a String longer than 10 bytes.
        flusher.write(RetainableByteBuffer.wrap("How now brown cow!", UTF_8), callback);

        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());

        assertTrue(incompleteFlush.get());

        assertThrows(TimeoutException.class, () -> callback.get(100, TimeUnit.MILLISECONDS));

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

        try (StacklessLogging _ = new StacklessLogging(WriteFlusher.class))
        {
            flusher.write(RetainableByteBuffer.wrap(BufferUtil.toBuffer("How now brown cow!")), callback);
            callback.get(100, TimeUnit.MILLISECONDS);
        }

        assertEquals("How now brown cow!", endPoint.takeOutputString());
        assertTrue(callback.isDone());
        assertFalse(incompleteFlush.get());
        assertTrue(flusher.isIdle());
    }

    @Test
    public void testCloseWhileBlocking()
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
        flusher.write(RetainableByteBuffer.wrap(BufferUtil.toBuffer("How now brown cow!")), callback);

        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());

        assertTrue(incompleteFlush.get());
        incompleteFlush.set(false);

        assertEquals("How now br", endPoint.takeOutputString());

        endPoint.close();
        flusher.completeWrite();

        assertTrue(callback.isDone());
        assertFalse(incompleteFlush.get());

        ExecutionException e = assertThrows(ExecutionException.class, callback::get);
        assertThat(e.getCause(), instanceOf(IOException.class));
        assertThat(e.getCause().getMessage(), containsString("CLOSED"));

        assertThrows(Exception.class, endPoint::takeOutputString);
        assertTrue(flusher.isFailed());
    }

    @Test
    public void testFailWhileBlocking()
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
        flusher.write(RetainableByteBuffer.wrap(BufferUtil.toBuffer("How now brown cow!")), callback);

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

        ExecutionException e = assertThrows(ExecutionException.class, callback::get);
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
            int concurrent = 5000;
            String reason = "THE_CAUSE";
            ConcurrentWriteFlusher[] flushers = new ConcurrentWriteFlusher[concurrent];
            FutureCallback[] futures = new FutureCallback[flushers.length];
            for (int i = 0; i < flushers.length; ++i)
            {
                int size = 5 + (i % 15);
                ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], size);
                ConcurrentWriteFlusher flusher = new ConcurrentWriteFlusher(endPoint, scheduler, random);
                flushers[i] = flusher;
                FutureCallback callback = new FutureCallback();
                futures[i] = callback;
                scheduler.schedule(() -> flusher.onFail(new Throwable(reason)), (i % 75) + 1, TimeUnit.MILLISECONDS);
                flusher.write(RetainableByteBuffer.wrap(
                    RetainableByteBuffer.wrap("How Now Brown Cow.", UTF_8),
                    RetainableByteBuffer.wrap(" The quick brown fox jumped over the lazy dog!", UTF_8)
                ), callback);
            }

            int completed = 0;
            int failed = 0;
            for (int i = 0; i < flushers.length; ++i)
            {
                try
                {
                    futures[i].get(15, TimeUnit.SECONDS);
                    assertEquals("How Now Brown Cow. The quick brown fox jumped over the lazy dog!", flushers[i].getContent(), "Flusher " + i);
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
    public void testConcurrentWrites() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 16);

        CountDownLatch flushLatch = new CountDownLatch(1);
        WriteFlusher flusher = new WriteFlusher(endPoint)
        {
            @Override
            protected boolean flush(SocketAddress address, RetainableByteBuffer buffer) throws IOException
            {
                try
                {
                    flushLatch.countDown();
                    Thread.sleep(2000);
                    return super.flush(address, buffer);
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
        new Thread(() -> flusher.write(RetainableByteBuffer.wrap(BufferUtil.toBuffer("foo")), Callback.NOOP)).start();
        assertTrue(flushLatch.await(1, TimeUnit.SECONDS));

        assertThrows(WritePendingException.class, () ->
        {
            // The second write throws WritePendingException.
            flusher.write(RetainableByteBuffer.wrap(BufferUtil.toBuffer("bar")), Callback.NOOP);
        });
    }

    @Test
    public void testConcurrentWriteAndOnFail()
    {
        assertThrows(ExecutionException.class, () ->
        {
            ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 16);

            WriteFlusher flusher = new WriteFlusher(endPoint)
            {
                @Override
                protected boolean flush(SocketAddress address, RetainableByteBuffer buffer) throws IOException
                {
                    boolean flushed = super.flush(address, buffer);
                    boolean notified = onFail(new Throwable());
                    assertTrue(notified);
                    return flushed;
                }

                @Override
                protected void onIncompleteFlush()
                {
                }
            };

            FutureCallback callback = new FutureCallback();
            flusher.write(RetainableByteBuffer.wrap(BufferUtil.toBuffer("foo")), callback);

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
        flusher.write(RetainableByteBuffer.wrap(BufferUtil.toBuffer(content)), callback);

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
