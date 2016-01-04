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

package org.eclipse.jetty.util;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class FutureCallbackTest
{
    @Test
    public void testNotDone()
    {
        FutureCallback fcb= new FutureCallback();
        Assert.assertFalse(fcb.isDone());
        Assert.assertFalse(fcb.isCancelled());
    }
    
    @Test
    public void testGetNotDone() throws Exception
    {
        FutureCallback fcb= new FutureCallback();
        
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
        FutureCallback fcb= new FutureCallback();
        fcb.succeeded();
        Assert.assertTrue(fcb.isDone());
        Assert.assertFalse(fcb.isCancelled());

        long start=System.currentTimeMillis();
        Assert.assertEquals(null,fcb.get());
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(500L));     
    }
    
    @Test
    public void testGetDone() throws Exception
    {
        final FutureCallback fcb= new FutureCallback();
        final CountDownLatch latch = new CountDownLatch(1);
        
        new Thread(new Runnable(){
            public void run()
            {
                latch.countDown();
                try{TimeUnit.MILLISECONDS.sleep(100);}catch(Exception e){e.printStackTrace();}
                fcb.succeeded();
            }
        }).start();
        
        latch.await();
        long start=System.currentTimeMillis();
        Assert.assertEquals(null,fcb.get(10000,TimeUnit.MILLISECONDS));
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.greaterThan(10L)); 
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(1000L)); 
        
        Assert.assertTrue(fcb.isDone());
        Assert.assertFalse(fcb.isCancelled());   
    }
    


    @Test
    public void testFailed() throws Exception
    {
        FutureCallback fcb= new FutureCallback();
        Exception ex=new Exception("FAILED");
        fcb.failed(ex);
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
        final FutureCallback fcb= new FutureCallback();
        final Exception ex=new Exception("FAILED");
        final CountDownLatch latch = new CountDownLatch(1);
        
        new Thread(new Runnable(){
            public void run()
            {
                latch.countDown();
                try{TimeUnit.MILLISECONDS.sleep(100);}catch(Exception e){e.printStackTrace();}
                fcb.failed(ex);
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
        FutureCallback fcb= new FutureCallback();
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
        final FutureCallback fcb= new FutureCallback();
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
