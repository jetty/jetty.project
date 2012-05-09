package org.eclipse.jetty.io;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.Callback;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.hamcrest.number.OrderingComparison.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class IOFutureTest
{
    @Test
    public void testReadyCompleted() throws Exception
    {
        IOFuture future = new DoneIOFuture();

        assertTrue(future.isDone());
        assertTrue(future.isComplete());

        long start=System.currentTimeMillis();
        future.block();
        Assert.assertThat(System.currentTimeMillis()-start,lessThan(10L));

        start=System.currentTimeMillis();
        future.block(1000,TimeUnit.MILLISECONDS);
        Assert.assertThat(System.currentTimeMillis()-start,lessThan(10L));

        final AtomicBoolean ready = new AtomicBoolean(false);
        final AtomicReference<Throwable> fail = new AtomicReference<>();

        future.setCallback(new Callback<Object>()
        {
            @Override
            public void completed(Object context)
            {
                ready.set(true);
            }

            @Override
            public void failed(Object context, Throwable cause)
            {
                fail.set(cause);
            }
        }, null);

        assertTrue(ready.get());
        assertNull(fail.get());
    }


    @Test
    public void testFailedCompleted() throws Exception
    {
        Exception ex=new Exception("failed");
        IOFuture future = new DoneIOFuture(ex);

        assertTrue(future.isDone());
        try
        {
            future.isComplete();
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            Assert.assertEquals(ex,e.getCause());
        }


        long start=System.currentTimeMillis();
        try
        {
            future.block();
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            Assert.assertEquals(ex,e.getCause());
        }
        Assert.assertThat(System.currentTimeMillis()-start,lessThan(10L));



        start=System.currentTimeMillis();
        try
        {
            future.block(1000,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            Assert.assertEquals(ex,e.getCause());
        }
        Assert.assertThat(System.currentTimeMillis()-start,lessThan(10L));


        final AtomicBoolean ready = new AtomicBoolean(false);
        final AtomicReference<Throwable> fail = new AtomicReference<>();

        future.setCallback(new Callback<Object>()
        {
            @Override
            public void completed(Object context)
            {
                ready.set(true);
            }

            @Override
            public void failed(Object context, Throwable cause)
            {
                fail.set(cause);
            }
        }, null);

        assertFalse(ready.get());
        assertEquals(ex,fail.get());
    }



    @Test
    public void testInCompleted() throws Exception
    {
        IOFuture future = new DispatchingIOFuture();

        assertFalse(future.isDone());
        assertFalse(future.isComplete());

        long start=System.currentTimeMillis();
        future.block(10,TimeUnit.MILLISECONDS);
        Assert.assertThat(System.currentTimeMillis()-start,greaterThan(9L));

        final AtomicBoolean ready = new AtomicBoolean(false);
        final AtomicReference<Throwable> fail = new AtomicReference<>();

        future.setCallback(new Callback<Object>()
        {
            @Override
            public void completed(Object context)
            {
                ready.set(true);
            }

            @Override
            public void failed(Object context, Throwable cause)
            {
                fail.set(cause);
            }
        }, null);

        assertFalse(ready.get());
        assertNull(fail.get());
    }


    @Test
    public void testBlockWaitsWhenNotCompleted() throws Exception
    {
        DispatchingIOFuture future = new DispatchingIOFuture();

        assertFalse(future.isDone());
        assertFalse(future.isComplete());

        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        future.setCallback(new Callback<Object>()
        {
            @Override
            public void completed(Object context)
            {
                completed.set(true);
            }

            @Override
            public void failed(Object context, Throwable cause)
            {
                failure.set(cause);
            }
        }, null);

        long sleep = 1000;
        long start = System.nanoTime();
        assertFalse(future.block(sleep, TimeUnit.MILLISECONDS));
        assertThat(System.nanoTime() - start, greaterThan(TimeUnit.MILLISECONDS.toNanos(sleep / 2)));

        assertFalse(completed.get());
        assertNull(failure.get());
    }

    @Test
    public void testTimedBlockWokenUpWhenCompleted() throws Exception
    {
        final DispatchingIOFuture future = new DispatchingIOFuture();

        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        future.setCallback(new Callback<Object>()
        {
            @Override
            public void completed(Object context)
            {
                completed.countDown();
            }

            @Override
            public void failed(Object context, Throwable cause)
            {
                failure.set(cause);
            }
        }, null);

        long start = System.nanoTime();
        final long delay = 500;
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    // Want the call to block() below to happen before the call to complete() here
                    TimeUnit.MILLISECONDS.sleep(delay);
                    future.complete();
                }
                catch (InterruptedException x)
                {
                    Assert.fail();
                }
            }
        }.start();

        assertTrue(future.block(delay * 4, TimeUnit.MILLISECONDS));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Assert.assertThat(elapsed, greaterThan(delay / 2));
        Assert.assertThat(elapsed, lessThan(delay * 2));

        assertTrue(future.isDone());
        assertTrue(future.isComplete());
        assertTrue(completed.await(delay * 4, TimeUnit.MILLISECONDS));
        assertNull(failure.get());
    }

    @Test
    public void testBlockWokenUpWhenCompleted() throws Exception
    {
        final DispatchingIOFuture future = new DispatchingIOFuture();

        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        future.setCallback(new Callback<Object>()
        {
            @Override
            public void completed(Object context)
            {
                completed.countDown();
            }

            @Override
            public void failed(Object context, Throwable cause)
            {
                failure.set(cause);
            }
        }, null);

        final long delay = 500;
        long start = System.nanoTime();
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    // Want the call to block() below to happen before the call to complete() here
                    TimeUnit.MILLISECONDS.sleep(delay);
                    future.complete();
                }
                catch (InterruptedException x)
                {
                    Assert.fail();
                }
            }
        }.start();

        future.block();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Assert.assertThat(elapsed, greaterThan(delay / 2));
        Assert.assertThat(elapsed, lessThan(delay * 2));

        assertTrue(future.isDone());
        assertTrue(future.isComplete());
        assertTrue(completed.await(delay * 4, TimeUnit.MILLISECONDS));
        assertNull(failure.get());
    }

    @Test
    public void testTimedBlockWokenUpOnFailure() throws Exception
    {
        final DispatchingIOFuture future = new DispatchingIOFuture();
        final Exception ex = new Exception("failed");

        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch failureLatch = new CountDownLatch(1);
        future.setCallback(new Callback<Object>()
        {
            @Override
            public void completed(Object context)
            {
                completed.set(true);
            }

            @Override
            public void failed(Object context, Throwable x)
            {
                failure.set(x);
                failureLatch.countDown();
            }
        }, null);

        final long delay = 500;
        long start = System.nanoTime();
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    // Want the call to block() below to happen before the call to fail() here
                    TimeUnit.MILLISECONDS.sleep(delay);
                    future.fail(ex);
                }
                catch (InterruptedException x)
                {
                    Assert.fail();
                }
            }
        }.start();

        try
        {
            future.block(delay * 4, TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Assert.assertSame(ex, e.getCause());
        }

        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Assert.assertThat(elapsed, greaterThan(delay / 2));
        Assert.assertThat(elapsed, lessThan(delay * 2));

        assertTrue(future.isDone());
        try
        {
            future.isComplete();
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            assertSame(ex, e.getCause());
        }

        assertFalse(completed.get());
        assertTrue(failureLatch.await(delay * 4, TimeUnit.MILLISECONDS));
        assertSame(ex, failure.get());
    }

    @Test
    public void testBlockWokenUpOnFailure() throws Exception
    {
        final DispatchingIOFuture future = new DispatchingIOFuture();
        final Exception ex = new Exception("failed");

        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch failureLatch = new CountDownLatch(1);
        future.setCallback(new Callback<Object>()
        {
            @Override
            public void completed(Object context)
            {
                completed.set(true);
            }

            @Override
            public void failed(Object context, Throwable x)
            {
                failure.set(x);
                failureLatch.countDown();
            }
        }, null);

        final long delay = 500;
        long start = System.nanoTime();
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    // Want the call to block() below to happen before the call to fail() here
                    TimeUnit.MILLISECONDS.sleep(delay);
                    future.fail(ex);
                }
                catch (InterruptedException x)
                {
                    Assert.fail();
                }
            }
        }.start();

        try
        {
            future.block();
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Assert.assertSame(ex, e.getCause());
        }

        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Assert.assertThat(elapsed, greaterThan(delay / 2));
        Assert.assertThat(elapsed, lessThan(delay * 2));

        assertTrue(future.isDone());
        try
        {
            future.isComplete();
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            assertSame(ex, e.getCause());
        }

        assertFalse(completed.get());
        assertTrue(failureLatch.await(delay * 4, TimeUnit.MILLISECONDS));
        assertSame(ex, failure.get());
    }
}
