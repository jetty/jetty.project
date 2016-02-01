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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.SharedBlockingCallback.Blocker;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class SharedBlockingCallbackTest
{
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
    
    public SharedBlockingCallbackTest()
    {
    }
    
    
    @Test
    public void testDone() throws Exception
    { 
        long start;
        try (Blocker blocker=sbcb.acquire())
        {
            blocker.succeeded();
            start=System.currentTimeMillis();
            blocker.block();
        }
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(500L));  
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
            start=System.currentTimeMillis();
            blocker.block();
        }
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.greaterThan(10L)); 
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(1000L)); 
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
            start=System.currentTimeMillis();
            Assert.assertEquals(ex,ee.getCause());
        }
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(100L));    
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
                start=System.currentTimeMillis();
                blocker.block();
            }
            Assert.fail();
        }
        catch(IOException ee)
        {
            Assert.assertEquals(ex,ee.getCause());
        }
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.greaterThan(10L)); 
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(1000L));
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
        long start=System.currentTimeMillis();
        try (Blocker blocker=sbcb.acquire())
        {
            Assert.assertThat(System.currentTimeMillis()-start,Matchers.greaterThan(10L)); 
            Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(500L)); 

            blocker.succeeded();
            blocker.block();
        }
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(600L)); 
        Assert.assertEquals(0,notComplete.get());     
    }

    @Test
    public void testBlockerClose() throws Exception
    {
        try (Blocker blocker=sbcb.acquire())
        {
            SharedBlockingCallback.LOG.info("Blocker not complete "+blocker+" warning is expected...");
        }
        
        Assert.assertEquals(1,notComplete.get());
    }
    
    @Test
    public void testBlockerTimeout() throws Exception
    {
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
            assertThat(blocker,not(equalTo(b0)));
            try
            {
                b0.succeeded();
                fail();
            }
            catch(Exception e)
            {
                assertThat(e,instanceOf(IllegalStateException.class));
                assertThat(e.getCause(),instanceOf(TimeoutException.class));
            }
            blocker.succeeded();
        }
    }
}
