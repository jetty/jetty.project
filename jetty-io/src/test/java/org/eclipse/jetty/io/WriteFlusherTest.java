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
import org.eclipse.jetty.util.FutureCallback;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class WriteFlusherTest
{
    @Mock
    EndPoint _endPointMock;

    ByteArrayEndPoint _endp;
    final AtomicBoolean _flushIncomplete = new AtomicBoolean(false);
    WriteFlusher _flusher;
    final String _context = new String("Context");

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
        assertTrue(callback.isDone());
        assertFalse(_flushIncomplete.get());
        assertEquals(_context,callback.get());
        assertEquals("How now brown cow!",_endp.takeOutputString());
    }

    @Test
    public void testClosedNoBlocking() throws Exception
    {
        _endp.close();

        FutureCallback<String> callback = new FutureCallback<>();
        _flusher.write(_context,callback,BufferUtil.toBuffer("How "),BufferUtil.toBuffer("now "),BufferUtil.toBuffer("brown "),BufferUtil.toBuffer("cow!"));
        assertTrue(callback.isDone());
        assertFalse(_flushIncomplete.get());
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
        assertTrue(callback.isDone());
        assertEquals(_context,callback.get());
        assertEquals("own cow!",_endp.takeOutputString());
        assertFalse(_flushIncomplete.get());
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
        assertTrue(callback.isDone());
        assertFalse(_flushIncomplete.get());
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
        assertTrue(callback.isDone());
        assertFalse(_flushIncomplete.get());
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

        final WriteFlusher writeFlusher = new WriteFlusher(_endPointMock)
        {
            @Override
            protected void onIncompleteFlushed()
            {
            }
        };

        endPointFlushExpectation(writeCalledLatch);

        executor.submit(new Writer(writeFlusher, new FutureCallback()));
        assertThat("Write has been called.", writeCalledLatch.await(5, TimeUnit.SECONDS), is(true));
        executor.submit(new FailedCaller(writeFlusher, failedCalledLatch)).get();
    }

    @Test(expected = WritePendingException.class)
    public void testConcurrentAccessToWrite() throws Throwable, InterruptedException, ExecutionException
    {
        ExecutorService executor = Executors.newFixedThreadPool(16);
        final CountDownLatch writeCalledLatch = new CountDownLatch(2);

        final WriteFlusher writeFlusher = new WriteFlusher(_endPointMock)
        {
            @Override
            protected void onIncompleteFlushed()
            {
            }
        };

        endPointFlushExpectation(writeCalledLatch);

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

    private void endPointFlushExpectation(final CountDownLatch writeCalledLatch) throws IOException
    {
        // add a small delay to make concurrent access more likely
        when(_endPointMock.flush(any(ByteBuffer[].class))).thenAnswer(new Answer<Object>()
        {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable
            {
                Object[] arguments = invocation.getArguments();
                ByteBuffer byteBuffer = (ByteBuffer)arguments[0];
                BufferUtil.flipToFill(byteBuffer); // pretend everything has written
                writeCalledLatch.countDown();
                Thread.sleep(1000);
                return null;
            }
        });
    }

    @Test
    public void testConcurrentAccessToIncompleteWriteAndFailed() throws IOException, InterruptedException, ExecutionException
    {
        ExecutorService executor = Executors.newFixedThreadPool(16);
        final CountDownLatch failedCalledLatch = new CountDownLatch(1);
        final CountDownLatch writeCalledLatch = new CountDownLatch(1);
        final CountDownLatch completeWrite = new CountDownLatch(1);

        final WriteFlusher writeFlusher = new WriteFlusher(_endPointMock)
        {
            protected void onIncompleteFlushed()
            {
                writeCalledLatch.countDown();
                System.out.println(System.currentTimeMillis() + ":" + Thread.currentThread().getName() + " onIncompleteFlushed: calling completeWrite " + writeCalledLatch.getCount()); //thomas
                try
                {
                    System.out.println(System.currentTimeMillis() + ":" + Thread.currentThread().getName() + " going to sleep " + getState());
                    Thread.sleep(1000);
                    System.out.println(System.currentTimeMillis() + ":" + Thread.currentThread().getName() + " woken up");
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }

                System.out.println(System.currentTimeMillis() + ":" + Thread.currentThread().getName() + " completeWrite call");
                completeWrite();
                completeWrite.countDown();
            }
        };

        endPointFlushExpectationPendingWrite();

        System.out.println(System.currentTimeMillis() + ":" + Thread.currentThread().getName() + " SUBMITTING WRITE");
        executor.submit(new Writer(writeFlusher, new FutureCallback()));
        assertThat("Write has been called.", writeCalledLatch.await(5, TimeUnit.SECONDS), is(true));
        System.out.println(System.currentTimeMillis() + ":" + Thread.currentThread().getName() + " SUBMITTING FAILED " + writeFlusher.getState());
        executor.submit(new FailedCaller(writeFlusher, failedCalledLatch));
        assertThat("Failed has been called.", failedCalledLatch.await(5, TimeUnit.SECONDS), is(true));
        System.out.println(System.currentTimeMillis() + ":" + Thread.currentThread().getName() + " Calling write again " + writeFlusher.getState());
        writeFlusher.write(_context, new FutureCallback<String>(), BufferUtil.toBuffer("foobar"));
        assertThat("completeWrite done", completeWrite.await(5, TimeUnit.SECONDS), is(true));
    }


    //TODO: combine with endPointFlushExpectation
    private void endPointFlushExpectationPendingWrite() throws IOException
    {
        when(_endPointMock.flush(any(ByteBuffer[].class))).thenAnswer(new Answer<Object>()
        {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable
            {
                Object[] arguments = invocation.getArguments();
                ByteBuffer byteBuffer = (ByteBuffer)arguments[0];
                int oldPos = byteBuffer.position();
                if (byteBuffer.remaining() == 2)
                {
                    Thread.sleep(1000);
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
            System.out.println(System.currentTimeMillis() + ":" + Thread.currentThread().getName() + " Calling writeFlusher.failed()");
            writeFlusher.failed(new IllegalStateException());
            System.out.println(System.currentTimeMillis() + ":" + Thread.currentThread().getName() + " COUNTING FAILED DOWN");
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
