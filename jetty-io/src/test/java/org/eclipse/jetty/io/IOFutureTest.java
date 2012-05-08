package org.eclipse.jetty.io;

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
        IOFuture future = new DispatchedIOFuture();

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
    public void testReady() throws Exception
    {
        DispatchedIOFuture future = new DispatchedIOFuture();

        assertFalse(future.isDone());
        assertFalse(future.isComplete());

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

        long start=System.currentTimeMillis();
        assertFalse(future.block(10,TimeUnit.MILLISECONDS));
        assertThat(System.currentTimeMillis()-start,greaterThan(9L));

        assertFalse(ready.get());
        assertNull(fail.get());


        start=System.currentTimeMillis();
        final DispatchedIOFuture f0=future;
        new Thread()
        {
            @Override
            public void run()
            {
                try{TimeUnit.MILLISECONDS.sleep(50);}catch(Exception e){e.printStackTrace();}
                f0.ready();
            }
        }.start();

        assertTrue(future.block(1000,TimeUnit.MILLISECONDS));
        Assert.assertThat(System.currentTimeMillis()-start,greaterThan(49L));
        Assert.assertThat(System.currentTimeMillis()-start,lessThan(1000L));

        assertTrue(future.isDone());
        assertTrue(future.isComplete());
        assertTrue(ready.get());
        assertNull(fail.get());

        ready.set(false);


        future = new DispatchedIOFuture();
        assertFalse(future.isDone());
        assertFalse(future.isComplete());
        start=System.currentTimeMillis();
        final DispatchedIOFuture f1=future;
        new Thread()
        {
            @Override
            public void run()
            {
                try{TimeUnit.MILLISECONDS.sleep(50);}catch(Exception e){e.printStackTrace();}
                f1.ready();
            }
        }.start();

        future.block();
        Assert.assertThat(System.currentTimeMillis()-start,greaterThan(49L));

        assertTrue(future.isDone());
        assertTrue(future.isComplete());
        assertFalse(ready.get()); // no callback set
        assertNull(fail.get());
    }


    @Test
    public void testFail() throws Exception
    {
        DispatchedIOFuture future = new DispatchedIOFuture();
        final Exception ex=new Exception("failed");

        assertFalse(future.isDone());
        assertFalse(future.isComplete());

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

        long start=System.currentTimeMillis();
        assertFalse(future.block(10,TimeUnit.MILLISECONDS));
        assertThat(System.currentTimeMillis()-start,greaterThan(9L));

        assertFalse(ready.get());
        assertNull(fail.get());

        start=System.currentTimeMillis();
        final DispatchedIOFuture f0=future;
        new Thread()
        {
            @Override
            public void run()
            {
                try{TimeUnit.MILLISECONDS.sleep(50);}catch(Exception e){e.printStackTrace();}
                f0.fail(ex);
            }
        }.start();

        try
        {
            future.block(1000,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            Assert.assertEquals(ex,e.getCause());
        }
        Assert.assertThat(System.currentTimeMillis()-start,greaterThan(49L));
        Assert.assertThat(System.currentTimeMillis()-start,lessThan(1000L));

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
        assertFalse(ready.get());
        assertEquals(ex,fail.get());

        future=new DispatchedIOFuture();
        ready.set(false);
        fail.set(null);

        assertFalse(future.isDone());
        assertFalse(future.isComplete());
        start=System.currentTimeMillis();
        final DispatchedIOFuture f1=future;
        new Thread()
        {
            @Override
            public void run()
            {
                try{TimeUnit.MILLISECONDS.sleep(50);}catch(Exception e){e.printStackTrace();}
                f1.fail(ex);
            }
        }.start();

        try
        {
            future.block();
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            Assert.assertEquals(ex,e.getCause());
        }
        Assert.assertThat(System.currentTimeMillis()-start,greaterThan(49L));

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
        assertFalse(ready.get()); // no callback set
        assertNull(fail.get()); // no callback set
    }
}
