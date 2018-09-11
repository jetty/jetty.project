//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.hamcrest.Matchers;

import org.junit.jupiter.api.Test;

public class FutureCallbackTest
{
    @Test
    public void testNotDone()
    {
        FutureCallback fcb= new FutureCallback();
        assertFalse(fcb.isDone());
        assertFalse(fcb.isCancelled());
    }
    
    @Test
    public void testGetNotDone() throws Exception
    {
        FutureCallback fcb= new FutureCallback();
        
        long start=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        try
        {
            fcb.get(500,TimeUnit.MILLISECONDS);
            fail("Expected a TimeoutException");
        }
        catch(TimeoutException e)
        {
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.greaterThan(50L));
    }

    @Test
    public void testDone() throws Exception
    {
        FutureCallback fcb= new FutureCallback();
        fcb.succeeded();
        assertTrue(fcb.isDone());
        assertFalse(fcb.isCancelled());

        long start=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        assertEquals(null,fcb.get());
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.lessThan(500L));
    }
    
    @Test
    public void testGetDone() throws Exception
    {
        final FutureCallback fcb= new FutureCallback();
        final CountDownLatch latch = new CountDownLatch(1);
        
        new Thread(new Runnable(){
            @Override
            public void run()
            {
                latch.countDown();
                try{TimeUnit.MILLISECONDS.sleep(100);}catch(Exception e){e.printStackTrace();}
                fcb.succeeded();
            }
        }).start();
        
        latch.await();
        long start=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        assertEquals(null,fcb.get(10000,TimeUnit.MILLISECONDS));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.lessThan(1000L));
        
        assertTrue(fcb.isDone());
        assertFalse(fcb.isCancelled());
    }
    


    @Test
    public void testFailed() throws Exception
    {
        FutureCallback fcb= new FutureCallback();
        Exception ex=new Exception("FAILED");
        fcb.failed(ex);
        assertTrue(fcb.isDone());
        assertFalse(fcb.isCancelled());

        long start=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        try
        {
            fcb.get();
            fail("Expected an ExecutionException");
        }
        catch(ExecutionException ee)
        {
            assertEquals(ex,ee.getCause());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.lessThan(100L));
    }
    
    @Test
    public void testGetFailed() throws Exception
    {
        final FutureCallback fcb= new FutureCallback();
        final Exception ex=new Exception("FAILED");
        final CountDownLatch latch = new CountDownLatch(1);
        
        new Thread(new Runnable(){
            @Override
            public void run()
            {
                latch.countDown();
                try{TimeUnit.MILLISECONDS.sleep(100);}catch(Exception e){e.printStackTrace();}
                fcb.failed(ex);
            }
        }).start();
        
        latch.await();
        long start=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        try
        {
            fcb.get(10000,TimeUnit.MILLISECONDS);
            fail("Expected an ExecutionException");
        }
        catch(ExecutionException ee)
        {
            assertEquals(ex,ee.getCause());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.lessThan(5000L));

        assertTrue(fcb.isDone());
        assertFalse(fcb.isCancelled());
    }
    


    @Test
    public void testCancelled() throws Exception
    {
        FutureCallback fcb= new FutureCallback();
        fcb.cancel(true);
        assertTrue(fcb.isDone());
        assertTrue(fcb.isCancelled());

        long start=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        try
        {
            fcb.get();
            fail("Expected a CancellationException");
        }
        catch(CancellationException e)
        {
            assertThat(e.getCause(),Matchers.instanceOf(CancellationException.class));
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.lessThan(100L));
    }
    
    @Test
    public void testGetCancelled() throws Exception
    {
        final FutureCallback fcb= new FutureCallback();
        final CountDownLatch latch = new CountDownLatch(1);
        
        new Thread(new Runnable(){
            @Override
            public void run()
            {
                latch.countDown();
                try{TimeUnit.MILLISECONDS.sleep(100);}catch(Exception e){e.printStackTrace();}
                fcb.cancel(true);
            }
        }).start();
        
        latch.await();
        long start=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        try
        {
            fcb.get(10000,TimeUnit.MILLISECONDS);
            fail("Expected a CancellationException");
        }
        catch(CancellationException e)
        {
            assertThat(e.getCause(),Matchers.instanceOf(CancellationException.class));
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.lessThan(1000L));

        assertTrue(fcb.isDone());
        assertTrue(fcb.isCancelled());
           
    }
    
    
}
