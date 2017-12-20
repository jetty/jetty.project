//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class CyclicTimeoutTest
{
    @Rule
    public TestTracker tracker = new TestTracker();

    private volatile boolean _expired;
    private ScheduledExecutorScheduler _timer = new ScheduledExecutorScheduler();
    private CyclicTimeout _timeout;

    @Before
    public void before() throws Exception
    {
        _expired=false;
        _timer.start();
        
        _timeout=new CyclicTimeout(_timer)
        {
            @Override
            public void onTimeoutExpired()
            {
                _expired = true;
            }
        };
        
        _timeout.schedule(1000,TimeUnit.MILLISECONDS);
    }

    @After
    public void after() throws Exception
    {
        _timeout.destroy();
        _timer.stop();
    }

    @Test
    public void testReschedule() throws Exception
    {
        for (int i=0;i<20;i++)
        {
            Thread.sleep(100);
            Assert.assertTrue(_timeout.schedule(1000,TimeUnit.MILLISECONDS));
        }
        Assert.assertFalse(_expired);
    }

    @Test
    public void testExpire() throws Exception
    {
        for (int i=0;i<5;i++)
        {
            Thread.sleep(100);
            Assert.assertTrue(_timeout.schedule(1000,TimeUnit.MILLISECONDS));
        }
        Thread.sleep(1500);
        Assert.assertTrue(_expired);
    }

    @Test
    public void testCancel() throws Exception
    {
        for (int i=0;i<5;i++)
        {
            Thread.sleep(100);
            Assert.assertTrue(_timeout.schedule(1000,TimeUnit.MILLISECONDS));
        }
        _timeout.cancel();
        Thread.sleep(1500);
        Assert.assertFalse(_expired);
    }

    @Test
    public void testShorten() throws Exception
    {
        for (int i=0;i<5;i++)
        {
            Thread.sleep(100);
            Assert.assertTrue(_timeout.schedule(1000,TimeUnit.MILLISECONDS));
        }
        Assert.assertTrue(_timeout.schedule(100,TimeUnit.MILLISECONDS));
        Thread.sleep(400);
        Assert.assertTrue(_expired);
    }

    @Test
    public void testLengthen() throws Exception
    {
        for (int i=0;i<5;i++)
        {
            Thread.sleep(100);
            Assert.assertTrue(_timeout.schedule(1000,TimeUnit.MILLISECONDS));
        }
        Assert.assertTrue(_timeout.schedule(10000,TimeUnit.MILLISECONDS));
        Thread.sleep(1500);
        Assert.assertFalse(_expired);
    }

    @Test
    public void testMultiple() throws Exception
    {
        Thread.sleep(1500);
        Assert.assertTrue(_expired);
        _expired=false;
        Assert.assertFalse(_timeout.schedule(500,TimeUnit.MILLISECONDS));
        Thread.sleep(1000);
        Assert.assertTrue(_expired);
        _expired=false;
        _timeout.schedule(500,TimeUnit.MILLISECONDS);
        Thread.sleep(1000);
        Assert.assertTrue(_expired);
    }

    @Test
    @Ignore
    public void testBusy() throws Exception
    {
        QueuedThreadPool pool = new QueuedThreadPool(200);
        pool.start();
        
        long test_until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);

        Assert.assertTrue(_timeout.schedule(100,TimeUnit.MILLISECONDS));
        while(System.nanoTime()<test_until)
        {
            CountDownLatch latch = new CountDownLatch(1);
            pool.execute(()->
            {
                _timeout.schedule(100,TimeUnit.MILLISECONDS);
                latch.countDown();
            });
            latch.await();
        }

        Assert.assertFalse(_expired);
        pool.stop();
    }
}
