package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class WriteFlusherTest
{
    @Mock
    private EndPoint _endPointMock;

    private WriteFlusher _flusher;

    private final AtomicBoolean _flushIncomplete = new AtomicBoolean(false);
    private final String _context = new String("Context");
    private ByteArrayEndPoint _endp;

    @Before
    public void before()
    {
        _endp = new ByteArrayEndPoint(new byte[]{},10);
        _flushIncomplete.set(false);
        _flusher = new WriteFlusher(_endp)
        {
            @Override
            protected void onIncompleteFlushed()
            {
                _flushIncomplete.set(true);
            }
        };
    }

    @Test
    public void testCompleteNoBlocking() throws Exception
    {
        _endp.setGrowOutput(true);

        FutureCallback<String> callback = new FutureCallback<>();
        _flusher.write(_context,callback,BufferUtil.toBuffer("How "),BufferUtil.toBuffer("now "),BufferUtil.toBuffer("brown "),BufferUtil.toBuffer("cow!"));
        assertCallbackIsDone(callback);
        assertFlushIsComplete();
        assertThat("context and callback.get() are equal", _context, equalTo(callback.get()));
        assertThat("string in endpoint matches expected string", "How now brown cow!",
                equalTo(_endp.takeOutputString()));
    }

    private void assertFlushIsComplete()
    {
        assertThat("flush is complete", _flushIncomplete.get(), is(false));
    }

    private void assertCallbackIsDone(FutureCallback<String> callback)
    {
        assertThat("callback is done", callback.isDone(), is(true));
    }

    @Test
    public void testClosedNoBlocking() throws Exception
    {
        _endp.close();

        FutureCallback<String> callback = new FutureCallback<>();
        _flusher.write(_context,callback,BufferUtil.toBuffer("How "),BufferUtil.toBuffer("now "),BufferUtil.toBuffer("brown "),BufferUtil.toBuffer("cow!"));
        assertCallbackIsDone(callback);
        assertFlushIsComplete();
        try
        {
            assertEquals(_context,callback.get());
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof IOException);
            Assert.assertThat(cause.getMessage(),Matchers.containsString("CLOSED"));
        }
        assertEquals("",_endp.takeOutputString());
    }


    @Test
    public void testCompleteBlocking() throws Exception
    {
        FutureCallback<String> callback = new FutureCallback<>();
        _flusher.write(_context,callback,BufferUtil.toBuffer("How "),BufferUtil.toBuffer("now "),BufferUtil.toBuffer("brown "),BufferUtil.toBuffer("cow!"));
        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());

        assertTrue(_flushIncomplete.get());
        try
        {
            assertEquals(_context,callback.get(10,TimeUnit.MILLISECONDS));
            Assert.fail();
        }
        catch (TimeoutException to)
        {
            _flushIncomplete.set(false);
        }

        assertEquals("How now br",_endp.takeOutputString());
        _flusher.completeWrite();
        assertCallbackIsDone(callback);
        assertEquals(_context,callback.get());
        assertEquals("own cow!",_endp.takeOutputString());
        assertFlushIsComplete();
    }

    @Test
    public void testCloseWhileBlocking() throws Exception
    {
        FutureCallback<String> callback = new FutureCallback<>();
        _flusher.write(_context,callback,BufferUtil.toBuffer("How "),BufferUtil.toBuffer("now "),BufferUtil.toBuffer("brown "),BufferUtil.toBuffer("cow!"));

        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());

        assertTrue(_flushIncomplete.get());
        try
        {
            assertEquals(_context,callback.get(10,TimeUnit.MILLISECONDS));
            Assert.fail();
        }
        catch (TimeoutException to)
        {
            _flushIncomplete.set(false);
        }

        assertEquals("How now br",_endp.takeOutputString());
        _endp.close();
        _flusher.completeWrite();
        assertCallbackIsDone(callback);
        assertFlushIsComplete();
        try
        {
            assertEquals(_context,callback.get());
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof IOException);
            Assert.assertThat(cause.getMessage(),Matchers.containsString("CLOSED"));
        }
        assertEquals("",_endp.takeOutputString());
    }

    @Test
    public void testFailWhileBlocking() throws Exception
    {
        FutureCallback<String> callback = new FutureCallback<>();
        _flusher.write(_context,callback,BufferUtil.toBuffer("How "),BufferUtil.toBuffer("now "),BufferUtil.toBuffer("brown "),BufferUtil.toBuffer("cow!"));

        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());

        assertTrue(_flushIncomplete.get());
        try
        {
            assertEquals(_context,callback.get(10,TimeUnit.MILLISECONDS));
            Assert.fail();
        }
        catch (TimeoutException to)
        {
            _flushIncomplete.set(false);
        }

        assertEquals("How now br", _endp.takeOutputString());
        _flusher.failed(new IOException("Failure"));
        _flusher.completeWrite();
        assertCallbackIsDone(callback);
        assertFlushIsComplete();
        try
        {
            assertEquals(_context,callback.get());
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof IOException);
            Assert.assertThat(cause.getMessage(),Matchers.containsString("Failure"));
        }
        assertEquals("", _endp.takeOutputString());
    }

    @Test
    public void testConcurrentAccessToWriteAndFailed() throws IOException, InterruptedException, ExecutionException
    {
        ExecutorService executor = Executors.newFixedThreadPool(16);
        final CountDownLatch failedCalledLatch = new CountDownLatch(1);
        final CountDownLatch writeCalledLatch = new CountDownLatch(1);
        final CountDownLatch writeCompleteLatch = new CountDownLatch(1);

        final WriteFlusher writeFlusher = new WriteFlusher(_endPointMock)
        {
            @Override
            public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers)
            {
                super.write(context, callback, buffers);
                writeCompleteLatch.countDown();
            }

            @Override
            protected void onIncompleteFlushed()
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
        assertThat("callback failed", callback.isFailed(), is(false));
        assertThat("write complete", writeCompleteLatch.await(5, TimeUnit.SECONDS), is(true));
        // in this testcase we more or less emulate that the write has successfully finished and we return from
        // EndPoint.flush() back to WriteFlusher.write(). Then someone calls failed. So the callback should have been
        // completed.
        assertThat("callback completed", callback.isCompleted(), is(true));
    }

    private class ExposingStateCallback extends FutureCallback
    {
        private boolean failed = false;
        private boolean completed = false;

        @Override
        public void completed(Object context)
        {
            completed = true;
            super.completed(context);
        }

        @Override
        public void failed(Object context, Throwable cause)
        {
            failed = true;
            super.failed(context, cause);
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

    @Ignore("Intermittent failures.") //TODO: fixme
    @Test(expected = WritePendingException.class)
    public void testConcurrentAccessToWrite() throws Throwable
    {
        ExecutorService executor = Executors.newFixedThreadPool(16);

        final WriteFlusher writeFlusher = new WriteFlusher(_endPointMock)
        {
            @Override
            protected void onIncompleteFlushed()
            {
            }
        };

        // in this test we just want to make sure that we called write twice at the same time
        when(_endPointMock.flush(any(ByteBuffer[].class))).thenAnswer(new Answer<Object>()
        {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable
            {
                // make sure we stay here, so write is called twice at the same time
                Thread.sleep(5000);
                return null;
            }
        });

        executor.submit(new Writer(writeFlusher, new FutureCallback()));
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
            int called = 0;

            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable
            {
                called++;
                Object[] arguments = invocation.getArguments();
                ByteBuffer byteBuffer = (ByteBuffer)arguments[0];
                BufferUtil.flipToFill(byteBuffer); // pretend everything has been written
                writeCalledLatch.countDown();
                failedCalledLatch.await(5, TimeUnit.SECONDS);
                return null;
            }
        });
    }

    @Test
    public void testConcurrentAccessToIncompleteWriteAndFailed() throws IOException, InterruptedException, ExecutionException
    {
        ExecutorService executor = Executors.newFixedThreadPool(16);
        final CountDownLatch failedCalledLatch = new CountDownLatch(1);
        final CountDownLatch onIncompleteFlushedCalledLatch = new CountDownLatch(1);
        final CountDownLatch writeCalledLatch = new CountDownLatch(1);
        final CountDownLatch completeWrite = new CountDownLatch(1);

        final WriteFlusher writeFlusher = new WriteFlusher(_endPointMock)
        {
            protected void onIncompleteFlushed()
            {
                onIncompleteFlushedCalledLatch.countDown();
                completeWrite();
                completeWrite.countDown();
            }
        };

        endPointFlushExpectationPendingWrite(writeCalledLatch, failedCalledLatch);

        ExposingStateCallback callback = new ExposingStateCallback();
        executor.submit(new Writer(writeFlusher, callback));
        assertThat("Write has been called.", writeCalledLatch.await(5, TimeUnit.SECONDS), is(true));
        executor.submit(new FailedCaller(writeFlusher, failedCalledLatch));
        assertThat("Failed has been called.", failedCalledLatch.await(5, TimeUnit.SECONDS), is(true));
        writeFlusher.write(_context, new FutureCallback<String>(), BufferUtil.toBuffer("foobar"));
        assertThat("completeWrite done", completeWrite.await(5, TimeUnit.SECONDS), is(true));
    }


    //TODO: combine with endPointFlushExpectation
    private void endPointFlushExpectationPendingWrite(final CountDownLatch writeCalledLatch, final CountDownLatch
            failedCalledLatch)
            throws
            IOException
    {
        when(_endPointMock.flush(any(ByteBuffer[].class))).thenAnswer(new Answer<Object>()
        {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable
            {
                writeCalledLatch.countDown();
                Object[] arguments = invocation.getArguments();
                ByteBuffer byteBuffer = (ByteBuffer)arguments[0];
                int oldPos = byteBuffer.position();
                if (byteBuffer.remaining() == 2)
                {
                    // make sure failed is called before we go on
                    failedCalledLatch.await(5, TimeUnit.SECONDS);
                    BufferUtil.flipToFill(byteBuffer);
                }
                else if (byteBuffer.remaining() == 3)
                {
                    byteBuffer.position(1); // pretend writing one byte
                    return 1;
                }
                else
                {
                    byteBuffer.position(byteBuffer.limit());
                }
                return byteBuffer.limit() - oldPos;
            }
        });
    }

    private static class FailedCaller implements Callable
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
            writeFlusher.failed(new IllegalStateException());
            failedCalledLatch.countDown();
            return null;
        }
    }

    private class Writer implements Callable
    {
        private final WriteFlusher writeFlusher;
        private FutureCallback<String> callback;

        public Writer(WriteFlusher writeFlusher, FutureCallback callback)
        {
            this.writeFlusher = writeFlusher;
            this.callback = callback;
        }

        @Override
        public FutureCallback call()
        {
            writeFlusher.write(_context, callback, BufferUtil.toBuffer("foo"));
            return callback;
        }
    }
}
