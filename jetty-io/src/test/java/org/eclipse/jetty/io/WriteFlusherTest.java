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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.util.SharedBlockingCallback.Blocker;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class WriteFlusherTest
{
    private final AtomicBoolean _flushIncomplete = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newFixedThreadPool(16);
    @Mock
    private EndPoint _endPointMock;
    private WriteFlusher _flusher;
    private ByteArrayEndPoint _endp;

    @Before
    public void before()
    {
        _endp = new ByteArrayEndPoint(new byte[]{}, 10);
        _flushIncomplete.set(false);
        _flusher = new WriteFlusher(_endp)
        {
            @Override
            protected void onIncompleteFlush()
            {
                _flushIncomplete.set(true);
            }
        };
    }

    @Test
    public void testIgnorePreviousFailures() throws Exception
    {
        _endp.setGrowOutput(true);

        FutureCallback callback = new FutureCallback();
        _flusher.onFail(new IOException("Ignored because no operation in progress"));
        _flusher.write(callback, BufferUtil.toBuffer("How "), BufferUtil.toBuffer("now "), BufferUtil.toBuffer("brown "), BufferUtil.toBuffer("cow!"));
        assertCallbackIsDone(callback);
        assertFlushIsComplete();
        assertThat("context and callback.get() are equal",callback.get() , equalTo(null));
        assertThat("string in endpoint matches expected string", "How now brown cow!",
                equalTo(_endp.takeOutputString()));
        assertTrue(_flusher.isIdle());
    }

    @Test
    public void testCompleteNoBlocking() throws Exception
    {
        _endp.setGrowOutput(true);

        FutureCallback callback = new FutureCallback();
        _flusher.write(callback, BufferUtil.toBuffer("How "), BufferUtil.toBuffer("now "), BufferUtil.toBuffer("brown "), BufferUtil.toBuffer("cow!"));
        assertCallbackIsDone(callback);
        assertFlushIsComplete();
        assertThat("context and callback.get() are equal", callback.get(), equalTo(null));
        assertThat("string in endpoint matches expected string", "How now brown cow!",
                equalTo(_endp.takeOutputString()));
        assertTrue(_flusher.isIdle());
    }

    private void assertFlushIsComplete()
    {
        assertThat("flush is complete", _flushIncomplete.get(), is(false));
    }

    private void assertCallbackIsDone(FutureCallback callback)
    {
        assertThat("callback is done", callback.isDone(), is(true));
    }

    @Test
    public void testClosedNoBlocking() throws Exception
    {
        _endp.close();

        FutureCallback callback = new FutureCallback();
        _flusher.write(callback, BufferUtil.toBuffer("How "), BufferUtil.toBuffer("now "), BufferUtil.toBuffer("brown "), BufferUtil.toBuffer("cow!"));
        assertCallbackIsDone(callback);
        assertFlushIsComplete();
        try
        {
            assertEquals(callback.get(),null);
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof IOException);
            Assert.assertThat(cause.getMessage(), Matchers.containsString("CLOSED"));
        }
        assertEquals("", _endp.takeOutputString());
        assertTrue(_flusher.isIdle());
    }


    @Test
    public void testCompleteBlocking() throws Exception
    {
        FutureCallback callback = new FutureCallback();
        _flusher.write(callback, BufferUtil.toBuffer("How "), BufferUtil.toBuffer("now "), BufferUtil.toBuffer("brown "), BufferUtil.toBuffer("cow!"));
        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());

        assertTrue(_flushIncomplete.get());
        try
        {
            assertEquals(callback.get(10, TimeUnit.MILLISECONDS),null);
            Assert.fail();
        }
        catch (TimeoutException to)
        {
            _flushIncomplete.set(false);
        }

        assertEquals("How now br", _endp.takeOutputString());
        _flusher.completeWrite();
        assertCallbackIsDone(callback);
        assertEquals(callback.get(),null);
        assertEquals("own cow!", _endp.takeOutputString());
        assertFlushIsComplete();
        assertTrue(_flusher.isIdle());
    }

    @Test
    public void testCloseWhileBlocking() throws Exception
    {
        FutureCallback callback = new FutureCallback();
        _flusher.write(callback, BufferUtil.toBuffer("How "), BufferUtil.toBuffer("now "), BufferUtil.toBuffer("brown "), BufferUtil.toBuffer("cow!"));

        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());

        assertTrue(_flushIncomplete.get());
        try
        {
            assertEquals(callback.get(10, TimeUnit.MILLISECONDS),null);
            Assert.fail();
        }
        catch (TimeoutException to)
        {
            _flushIncomplete.set(false);
        }

        assertEquals("How now br", _endp.takeOutputString());
        _endp.close();
        _flusher.completeWrite();
        assertCallbackIsDone(callback);
        assertFlushIsComplete();
        try
        {
            assertEquals(callback.get(),null);
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof IOException);
            Assert.assertThat(cause.getMessage(), Matchers.containsString("CLOSED"));
        }
        assertEquals("", _endp.takeOutputString());
        assertTrue(_flusher.isIdle());
    }

    @Test
    public void testFailWhileBlocking() throws Exception
    {
        FutureCallback callback = new FutureCallback();
        _flusher.write(callback, BufferUtil.toBuffer("How "), BufferUtil.toBuffer("now "), BufferUtil.toBuffer("brown "), BufferUtil.toBuffer("cow!"));

        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());

        assertTrue(_flushIncomplete.get());
        try
        {
            assertEquals(callback.get(10, TimeUnit.MILLISECONDS),null);
            Assert.fail();
        }
        catch (TimeoutException to)
        {
            _flushIncomplete.set(false);
        }

        assertEquals("How now br", _endp.takeOutputString());
        _flusher.onFail(new IOException("Failure"));
        _flusher.completeWrite();
        assertCallbackIsDone(callback);
        assertFlushIsComplete();
        try
        {
            assertEquals(callback.get(),null);
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof IOException);
            Assert.assertThat(cause.getMessage(), Matchers.containsString("Failure"));
        }
        assertEquals("", _endp.takeOutputString());

        assertTrue(_flusher.isIdle());
    }

    private static class ConcurrentFlusher extends WriteFlusher implements Runnable
    {
        final ByteArrayEndPoint _endp;
        final SecureRandom _random;
        final ScheduledThreadPoolExecutor _scheduler;
        final StringBuilder _content = new StringBuilder();

        ConcurrentFlusher(ByteArrayEndPoint endp, SecureRandom random, ScheduledThreadPoolExecutor scheduler)
        {
            super(endp);
            _endp = endp;
            _random = random;
            _scheduler = scheduler;
        }

        @Override
        protected void onIncompleteFlush()
        {
            _scheduler.schedule(this, 1 + _random.nextInt(9), TimeUnit.MILLISECONDS);
        }

        @Override
        public synchronized void run()
        {
            _content.append(_endp.takeOutputString());
            completeWrite();
        }

        @Override
        public synchronized String toString()
        {
            _content.append(_endp.takeOutputString());
            return _content.toString();
        }
    }

    @Test
    public void testConcurrent() throws Exception
    {
        final SecureRandom random = new SecureRandom();
        final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(100);


        ConcurrentFlusher[] flushers = new ConcurrentFlusher[50000];
        FutureCallback[] futures = new FutureCallback[flushers.length];
        for (int i = 0; i < flushers.length; i++)
        {
            int size = 5 + random.nextInt(15);
            ByteArrayEndPoint endp = new ByteArrayEndPoint(new byte[]{}, size);

            final ConcurrentFlusher flusher = new ConcurrentFlusher(endp, random, scheduler);
            flushers[i] = flusher;
            final FutureCallback callback = new FutureCallback();
            futures[i] = callback;
            scheduler.schedule(new Runnable()
            {
                @Override
                public void run()
                {
                    flusher.onFail(new Throwable("THE CAUSE"));
                }
            }
                    , random.nextInt(75) + 1, TimeUnit.MILLISECONDS);
            flusher.write(callback, BufferUtil.toBuffer("How Now Brown Cow."), BufferUtil.toBuffer(" The quick brown fox jumped over the lazy dog!"));
        }

        int completed = 0;
        int failed = 0;

        for (int i = 0; i < flushers.length; i++)
        {
            try
            {
                futures[i].get();
                assertEquals("How Now Brown Cow. The quick brown fox jumped over the lazy dog!", flushers[i].toString());
                completed++;
            }
            catch (Exception e)
            {
                assertThat(e.getMessage(), Matchers.containsString("THE CAUSE"));
                failed++;
            }
        }

        assertThat(completed, Matchers.greaterThan(0));
        assertThat(failed, Matchers.greaterThan(0));

        scheduler.shutdown();
    }

    @Test
    public void testConcurrentAccessToWriteAndOnFail() throws Exception
    {
        // TODO review this test - It was changed for the boolean flush return, but not really well inspected

        final CountDownLatch failedCalledLatch = new CountDownLatch(1);
        final CountDownLatch writeCalledLatch = new CountDownLatch(1);
        final CountDownLatch writeCompleteLatch = new CountDownLatch(1);

        final WriteFlusher writeFlusher = new WriteFlusher(_endPointMock)
        {
            @Override
            public void write(Callback callback, ByteBuffer... buffers)
            {
                super.write(callback, buffers);
                writeCompleteLatch.countDown();
            }

            @Override
            protected void onIncompleteFlush()
            {
            }
        };

        endPointFlushExpectation(writeCalledLatch, failedCalledLatch);

        ExposingStateCallback callback = new ExposingStateCallback();
        executor.submit(new Writer(writeFlusher, callback));
        assertThat("Write has been called.", writeCalledLatch.await(5, TimeUnit.SECONDS), is(true));
        executor.submit(new FailedCaller(writeFlusher, failedCalledLatch)).get();


        // callback failed is NOT called because in WRITING state failed() doesn't know about the callback. However
        // either the write succeeds or we get an IOException which will call callback.failed()
        assertThat("write complete", writeCompleteLatch.await(5, TimeUnit.SECONDS), is(true));


        // in this testcase we more or less emulate that the write has successfully finished and we return from
        // EndPoint.flush() back to WriteFlusher.write(). Then someone calls failed. So the callback should have been
        // completed.
        try
        {
            callback.get(5,TimeUnit.SECONDS);
            assertThat("callback completed", callback.isCompleted(), is(true));
            assertThat("callback failed", callback.isFailed(), is(false));
        }
        catch(ExecutionException e)
        {
            // ignored because failure is expected
            assertThat("callback failed", callback.isFailed(), is(true));
        }
        assertThat("callback completed", callback.isDone(), is(true));
    }

    @Test
    public void testPendingWriteDoesNotStoreConsumedBuffers() throws Exception
    {
        int toWrite = _endp.getOutput().capacity();
        byte[] chunk1 = new byte[toWrite / 2];
        Arrays.fill(chunk1, (byte)1);
        ByteBuffer buffer1 = ByteBuffer.wrap(chunk1);
        byte[] chunk2 = new byte[toWrite];
        Arrays.fill(chunk1, (byte)2);
        ByteBuffer buffer2 = ByteBuffer.wrap(chunk2);

        _flusher.write(Callback.NOOP, buffer1, buffer2);
        assertTrue(_flushIncomplete.get());
        assertFalse(buffer1.hasRemaining());

        // Reuse buffer1
        buffer1.clear();
        Arrays.fill(chunk1, (byte)3);
        int remaining1 = buffer1.remaining();

        // Complete the write
        _endp.takeOutput();
        _flusher.completeWrite();

        // Make sure buffer1 is unchanged
        assertEquals(remaining1, buffer1.remaining());
    }

    private class ExposingStateCallback extends FutureCallback
    {
        private boolean failed = false;
        private boolean completed = false;

        @Override
        public void succeeded()
        {
            completed = true;
            super.succeeded();
        }

        @Override
        public void failed(Throwable cause)
        {
            failed = true;
            super.failed(cause);
        }

        public boolean isFailed()
        {
            return failed;
        }

        public boolean isCompleted()
        {
            return completed;
        }
    }

    @Test(expected = WritePendingException.class)
    public void testConcurrentAccessToWrite() throws Throwable
    {
        final CountDownLatch flushCalledLatch = new CountDownLatch(1);

        final WriteFlusher writeFlusher = new WriteFlusher(_endPointMock)
        {
            @Override
            protected void onIncompleteFlush()
            {
            }
        };

        // in this test we just want to make sure that we called write twice at the same time
        when(_endPointMock.flush(any(ByteBuffer[].class))).thenAnswer(new Answer<Object>()
        {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable
            {
                flushCalledLatch.countDown();
                // make sure we stay here, so write is called twice at the same time
                Thread.sleep(5000);
                return Boolean.TRUE;
            }
        });

        executor.submit(new Writer(writeFlusher, new FutureCallback()));
        // make sure that we call .get() on the write that executed second by waiting on this latch
        assertThat("Flush has been called once", flushCalledLatch.await(5, TimeUnit.SECONDS), is(true));
        try
        {
            executor.submit(new Writer(writeFlusher, new FutureCallback())).get();
        }
        catch (ExecutionException e)
        {
            throw e.getCause();
        }
    }

    private void endPointFlushExpectation(final CountDownLatch writeCalledLatch,
                                          final CountDownLatch failedCalledLatch) throws IOException
    {
        when(_endPointMock.flush(any(ByteBuffer[].class))).thenAnswer(new Answer<Object>()
        {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable
            {
                Object[] arguments = invocation.getArguments();
                ByteBuffer byteBuffer = (ByteBuffer)arguments[0];
                BufferUtil.flipToFill(byteBuffer); // pretend everything has been written
                writeCalledLatch.countDown();
                failedCalledLatch.await(5, TimeUnit.SECONDS);
                return Boolean.TRUE;
            }
        });
    }

    @Test
    public void testConcurrentAccessToIncompleteWriteAndOnFail() throws Exception
    {
        final CountDownLatch failedCalledLatch = new CountDownLatch(1);
        final CountDownLatch onIncompleteFlushedCalledLatch = new CountDownLatch(1);
        final CountDownLatch writeCalledLatch = new CountDownLatch(1);
        final CountDownLatch completeWrite = new CountDownLatch(1);

        final WriteFlusher writeFlusher = new WriteFlusher(new EndPointConcurrentAccessToIncompleteWriteAndOnFailMock(writeCalledLatch, failedCalledLatch))
        {
            @Override
            protected void onIncompleteFlush()
            {
                onIncompleteFlushedCalledLatch.countDown();
                try
                {
                    failedCalledLatch.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
                completeWrite();
                completeWrite.countDown();
            }
        };

        ExposingStateCallback callback = new ExposingStateCallback();
        executor.submit(new Writer(writeFlusher, callback));
        assertThat("Write has been called.", writeCalledLatch.await(5, TimeUnit.SECONDS), is(true));
        // make sure we're in pending state when calling onFail
        assertThat("onIncompleteFlushed has been called.", onIncompleteFlushedCalledLatch.await(5,
                TimeUnit.SECONDS), is(true));
        executor.submit(new FailedCaller(writeFlusher, failedCalledLatch));
        assertThat("Failed has been called.", failedCalledLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("completeWrite done", completeWrite.await(5, TimeUnit.SECONDS), is(true));
        // when we fail in PENDING state, we should have called callback.failed()
        assertThat("callback failed has been called", callback.isFailed(), is(true));
        assertThat("callback complete has not been called", callback.isCompleted(), is(false));
    }

    private static class EndPointConcurrentAccessToIncompleteWriteAndOnFailMock extends ByteArrayEndPoint
    {
        private final CountDownLatch writeCalledLatch;
        private final CountDownLatch failedCalledLatch;
        private final AtomicBoolean stalled=new AtomicBoolean(false);

        public EndPointConcurrentAccessToIncompleteWriteAndOnFailMock(CountDownLatch writeCalledLatch, CountDownLatch failedCalledLatch)
        {
            this.writeCalledLatch = writeCalledLatch;
            this.failedCalledLatch = failedCalledLatch;
        }

        @Override
        public boolean flush(ByteBuffer... buffers) throws IOException
        {
            writeCalledLatch.countDown();
            ByteBuffer byteBuffer = buffers[0];
            int oldPos = byteBuffer.position();
            if (byteBuffer.remaining() == 2)
            {
                // make sure we stall at least once
                if (!stalled.get())
                {
                    stalled.set(true);
                    return false;
                }

                // make sure failed is called before we go on
                try
                {
                    failedCalledLatch.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
                BufferUtil.flipToFill(byteBuffer);
            }
            else if (byteBuffer.remaining() == 3)
            {
                byteBuffer.position(1); // pretend writing one byte
            }
            else
            {
                byteBuffer.position(byteBuffer.limit());
            }

            for (ByteBuffer b: buffers)
                if (BufferUtil.hasContent(b))
                    return false;
            return true;
        }
    }

    @Test
    public void testIterationOnNonBlockedStall() throws Exception
    {
        final Exchanger<Integer> exchange = new Exchanger<>();
        final AtomicInteger window = new AtomicInteger(10);
        EndPointIterationOnNonBlockedStallMock endp=new EndPointIterationOnNonBlockedStallMock(window);
        final WriteFlusher writeFlusher = new WriteFlusher(endp)
        {
            @Override
            protected void onIncompleteFlush()
            {
                executor.submit(new Runnable()
                {
                    public void run()
                    {
                        try
                        {
                            while(window.get()==0)
                                window.addAndGet(exchange.exchange(0));
                            completeWrite();
                        }
                        catch(Throwable th)
                        {
                            th.printStackTrace();
                        }
                    }
                });

            }
        };

        try(Blocker blocker = new SharedBlockingCallback().acquire())
        {
            writeFlusher.write(blocker,BufferUtil.toBuffer("How "),BufferUtil.toBuffer("now "),BufferUtil.toBuffer("brown "),BufferUtil.toBuffer("cow."));
            exchange.exchange(0);

            Assert.assertThat(endp.takeOutputString(StandardCharsets.US_ASCII),Matchers.equalTo("How now br"));

            exchange.exchange(1);
            exchange.exchange(0);

            Assert.assertThat(endp.takeOutputString(StandardCharsets.US_ASCII),Matchers.equalTo("o"));

            exchange.exchange(8);
            blocker.block();
        }

        Assert.assertThat(endp.takeOutputString(StandardCharsets.US_ASCII),Matchers.equalTo("wn cow."));

    }

    private static class EndPointIterationOnNonBlockedStallMock extends ByteArrayEndPoint
    {
        final AtomicInteger _window;

        public EndPointIterationOnNonBlockedStallMock(AtomicInteger window)
        {
            _window=window;
        }

        @Override
        public boolean flush(ByteBuffer... buffers) throws IOException
        {
            ByteBuffer byteBuffer = buffers[0];

            if (_window.get()>0 && byteBuffer.hasRemaining())
            {
                // consume 1 byte
                byte one = byteBuffer.get(byteBuffer.position());
                if (super.flush(ByteBuffer.wrap(new byte[]{one})))
                {
                    _window.decrementAndGet();
                    byteBuffer.position(byteBuffer.position()+1);
                }
            }
            for (ByteBuffer b: buffers)
                if (BufferUtil.hasContent(b))
                    return false;
            return true;
        }
    }


    private static class FailedCaller implements Callable<FutureCallback>
    {
        private final WriteFlusher writeFlusher;
        private CountDownLatch failedCalledLatch;

        public FailedCaller(WriteFlusher writeFlusher, CountDownLatch failedCalledLatch)
        {
            this.writeFlusher = writeFlusher;
            this.failedCalledLatch = failedCalledLatch;
        }

        @Override
        public FutureCallback call()
        {
            writeFlusher.onFail(new IllegalStateException());
            failedCalledLatch.countDown();
            return null;
        }
    }

    private class Writer implements Callable<FutureCallback>
    {
        private final WriteFlusher writeFlusher;
        private FutureCallback callback;

        public Writer(WriteFlusher writeFlusher, FutureCallback callback)
        {
            this.writeFlusher = writeFlusher;
            this.callback = callback;
        }

        @Override
        public FutureCallback call()
        {
            writeFlusher.write(callback, BufferUtil.toBuffer("foo"));
            return callback;
        }
    }
}
