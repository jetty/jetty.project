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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.SharedBlockingCallback.Blocker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class SharedBlockingCallbackTest
{
    private static final Logger LOG = Log.getLogger(SharedBlockingCallback.class);

    final AtomicInteger notComplete = new AtomicInteger();
    final SharedBlockingCallback sbcb= new SharedBlockingCallback()
    {
        @Override
        protected long getIdleTimeout()
        {
            return 150;
        }

        @Override
        protected void notComplete(Blocker blocker)
        {
            super.notComplete(blocker);
            notComplete.incrementAndGet();
        }
    };

    @Test
    public void testDone() throws Exception
    { 
        long start;
        try (Blocker blocker=sbcb.acquire())
        {
            blocker.succeeded();
            start=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            blocker.block();
        }
        Assert.assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.lessThan(500L));  
        Assert.assertEquals(0,notComplete.get());   
    }
    
    @Test
    public void testGetDone() throws Exception
    {
        long start;
        try (final Blocker blocker=sbcb.acquire())
        {
            final CountDownLatch latch = new CountDownLatch(1);

            new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    latch.countDown();
                    try{TimeUnit.MILLISECONDS.sleep(100);}catch(Exception e){e.printStackTrace();}
                    blocker.succeeded();
                }
            }).start();

            latch.await();
            start=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            blocker.block();
        }
        Assert.assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.greaterThan(10L)); 
        Assert.assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.lessThan(1000L)); 
        Assert.assertEquals(0,notComplete.get());   
    }
    
    @Test
    public void testFailed() throws Exception
    {
        final Exception ex = new Exception("FAILED");
        long start=Long.MIN_VALUE;
        try
        {
            try (final Blocker blocker=sbcb.acquire())
            {
                blocker.failed(ex);
                blocker.block();
            }
            Assert.fail();
        }
        catch(IOException ee)
        {
            start=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            Assert.assertEquals(ex,ee.getCause());
        }
        Assert.assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.lessThan(100L));    
        Assert.assertEquals(0,notComplete.get());    
    }
    
    @Test
    public void testGetFailed() throws Exception
    {
        final Exception ex = new Exception("FAILED");
        long start=Long.MIN_VALUE;
        final CountDownLatch latch = new CountDownLatch(1);

        try
        {
            try (final Blocker blocker=sbcb.acquire())
            {

                new Thread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        latch.countDown();
                        try{TimeUnit.MILLISECONDS.sleep(100);}catch(Exception e){e.printStackTrace();}
                        blocker.failed(ex);
                    }
                }).start();

                latch.await();
                start=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                blocker.block();
            }
            Assert.fail();
        }
        catch(IOException ee)
        {
            Assert.assertEquals(ex,ee.getCause());
        }
        Assert.assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.greaterThan(10L)); 
        Assert.assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.lessThan(1000L));
        Assert.assertEquals(0,notComplete.get());   
    }


    @Test
    public void testAcquireBlocked() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    try (Blocker blocker=sbcb.acquire())
                    {
                        latch.countDown();
                        TimeUnit.MILLISECONDS.sleep(100);
                        blocker.succeeded();
                        blocker.block();
                    }
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        }).start();
        
        
        latch.await();
        long start=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        try (Blocker blocker=sbcb.acquire())
        {
            Assert.assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.greaterThan(10L)); 
            Assert.assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.lessThan(500L)); 

            blocker.succeeded();
            blocker.block();
        }
        Assert.assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())-start,Matchers.lessThan(600L)); 
        Assert.assertEquals(0,notComplete.get());     
    }

    @Test
    public void testBlockerClose() throws Exception
    {
        try (Blocker blocker=sbcb.acquire())
        {
            LOG.info("Blocker not complete "+blocker+" warning is expected...");
        }
        
        Assert.assertEquals(1,notComplete.get());
    }
    
    @Test
    public void testBlockerTimeout() throws Exception
    {
        LOG.info("Succeeded after ... warning is expected...");
        Blocker b0=null;
        try
        {
            try (Blocker blocker=sbcb.acquire())
            {
                b0=blocker;
                Thread.sleep(400);
                blocker.block();
            }
            fail();
        }
        catch(IOException e)
        {
            Throwable cause = e.getCause();
            assertThat(cause,instanceOf(TimeoutException.class));
        }
        
        Assert.assertEquals(0,notComplete.get());

        try (Blocker blocker=sbcb.acquire())
        {
            assertThat(blocker,not(sameInstance(b0)));
            b0.succeeded();
            blocker.succeeded();
        }
    }

    @Test
    public void testInterruptedException() throws Exception
    {
        Blocker blocker0;
        try (Blocker blocker = sbcb.acquire())
        {
            blocker0 = blocker;
            Thread.currentThread().interrupt();
            try
            {
                blocker.block();
                fail();
            }
            catch (InterruptedIOException ignored)
            {
            }
        }
        // Blocker.close() has been called by try-with-resources.
        // Simulate callback completion, must not throw.
        LOG.info("Succeeded after ... warning is expected...");
        blocker0.succeeded();

        try (Blocker blocker = sbcb.acquire())
        {
            assertThat(blocker, not(sameInstance(blocker0)));
            blocker.succeeded();
        }
    }
}
