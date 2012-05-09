package org.eclipse.jetty.util;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;


import org.eclipse.jetty.util.FutureCallback;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class FutureCallbackTest
{
    @Test
    public void testNotDone()
    {
        FutureCallback<String> fcb= new FutureCallback<>();
        Assert.assertFalse(fcb.isDone());
        Assert.assertFalse(fcb.isCancelled());
    }
    
    @Test
    public void testGetNotDone() throws Exception
    {
        FutureCallback<String> fcb= new FutureCallback<>();
        
        long start=System.currentTimeMillis();
        try
        {
            fcb.get(500,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch(TimeoutException e)
        {
        }
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.greaterThan(50L));
    }

    @Test
    public void testDone() throws Exception
    {
        FutureCallback<String> fcb= new FutureCallback<>();
        fcb.completed("Ctx");
        Assert.assertTrue(fcb.isDone());
        Assert.assertFalse(fcb.isCancelled());

        long start=System.currentTimeMillis();
        Assert.assertEquals("Ctx",fcb.get());
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(500L));     
    }
    
    @Test
    public void testGetDone() throws Exception
    {
        final FutureCallback<String> fcb= new FutureCallback<>();
        final CountDownLatch latch = new CountDownLatch(1);
        
        new Thread(new Runnable(){
            public void run()
            {
                latch.countDown();
                try{TimeUnit.MILLISECONDS.sleep(100);}catch(Exception e){e.printStackTrace();}
                fcb.completed("Ctx");
            }
        }).start();
        
        latch.await();
        long start=System.currentTimeMillis();
        Assert.assertEquals("Ctx",fcb.get(10000,TimeUnit.MILLISECONDS));
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.greaterThan(10L)); 
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(1000L)); 
        
        Assert.assertTrue(fcb.isDone());
        Assert.assertFalse(fcb.isCancelled());   
    }
    


    @Test
    public void testFailed() throws Exception
    {
        FutureCallback<String> fcb= new FutureCallback<>();
        Exception ex=new Exception("FAILED");
        fcb.failed("Ctx",ex);
        Assert.assertTrue(fcb.isDone());
        Assert.assertFalse(fcb.isCancelled());

        long start=System.currentTimeMillis();
        try
        {
            fcb.get();
            Assert.fail();
        }
        catch(ExecutionException ee)
        {
            Assert.assertEquals(ex,ee.getCause());
        }
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(100L));     
    }
    
    @Test
    public void testGetFailed() throws Exception
    {
        final FutureCallback<String> fcb= new FutureCallback<>();
        final Exception ex=new Exception("FAILED");
        final CountDownLatch latch = new CountDownLatch(1);
        
        new Thread(new Runnable(){
            public void run()
            {
                latch.countDown();
                try{TimeUnit.MILLISECONDS.sleep(100);}catch(Exception e){e.printStackTrace();}
                fcb.failed("Ctx",ex);
            }
        }).start();
        
        latch.await();
        long start=System.currentTimeMillis();
        try
        {
            fcb.get(10000,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch(ExecutionException ee)
        {
            Assert.assertEquals(ex,ee.getCause());
        }
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.greaterThan(10L)); 
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(1000L));

        Assert.assertTrue(fcb.isDone());
        Assert.assertFalse(fcb.isCancelled());
    }
    


    @Test
    public void testCancelled() throws Exception
    {
        FutureCallback<String> fcb= new FutureCallback<>();
        fcb.cancel(true);
        Assert.assertTrue(fcb.isDone());
        Assert.assertTrue(fcb.isCancelled());

        long start=System.currentTimeMillis();
        try
        {
            fcb.get();
            Assert.fail();
        }
        catch(CancellationException e)
        {
            Assert.assertThat(e.getCause(),Matchers.instanceOf(CancellationException.class));
        }
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(100L));     
    }
    
    @Test
    public void testGetCancelled() throws Exception
    {
        final FutureCallback<String> fcb= new FutureCallback<>();
        final CountDownLatch latch = new CountDownLatch(1);
        
        new Thread(new Runnable(){
            public void run()
            {
                latch.countDown();
                try{TimeUnit.MILLISECONDS.sleep(100);}catch(Exception e){e.printStackTrace();}
                fcb.cancel(true);
            }
        }).start();
        
        latch.await();
        long start=System.currentTimeMillis();
        try
        {
            fcb.get(10000,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch(CancellationException e)
        {
            Assert.assertThat(e.getCause(),Matchers.instanceOf(CancellationException.class));
        }
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.greaterThan(10L)); 
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(1000L));

        Assert.assertTrue(fcb.isDone());
        Assert.assertTrue(fcb.isCancelled());
           
    }
    
    
}
