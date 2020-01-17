//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CyclicTimeoutTest
{
    private volatile boolean _expired;
    private ScheduledExecutorScheduler _timer = new ScheduledExecutorScheduler();
    private CyclicTimeout _timeout;

    @BeforeEach
    public void before() throws Exception
    {
        _expired = false;
        _timer.start();

        _timeout = new CyclicTimeout(_timer)
        {
            @Override
            public void onTimeoutExpired()
            {
                _expired = true;
            }
        };

        _timeout.schedule(1000, TimeUnit.MILLISECONDS);
    }

    @AfterEach
    public void after() throws Exception
    {
        _timeout.destroy();
        _timer.stop();
    }

    @Test
    public void testReschedule() throws Exception
    {
        for (int i = 0; i < 20; i++)
        {
            Thread.sleep(100);
            assertTrue(_timeout.schedule(1000, TimeUnit.MILLISECONDS));
        }
        assertFalse(_expired);
    }

    @Test
    public void testExpire() throws Exception
    {
        for (int i = 0; i < 5; i++)
        {
            Thread.sleep(100);
            assertTrue(_timeout.schedule(1000, TimeUnit.MILLISECONDS));
        }
        Thread.sleep(1500);
        assertTrue(_expired);
    }

    @Test
    public void testCancel() throws Exception
    {
        for (int i = 0; i < 5; i++)
        {
            Thread.sleep(100);
            assertTrue(_timeout.schedule(1000, TimeUnit.MILLISECONDS));
        }
        _timeout.cancel();
        Thread.sleep(1500);
        assertFalse(_expired);
    }

    @Test
    public void testShorten() throws Exception
    {
        for (int i = 0; i < 5; i++)
        {
            Thread.sleep(100);
            assertTrue(_timeout.schedule(1000, TimeUnit.MILLISECONDS));
        }
        assertTrue(_timeout.schedule(100, TimeUnit.MILLISECONDS));
        Thread.sleep(400);
        assertTrue(_expired);
    }

    @Test
    public void testLengthen() throws Exception
    {
        for (int i = 0; i < 5; i++)
        {
            Thread.sleep(100);
            assertTrue(_timeout.schedule(1000, TimeUnit.MILLISECONDS));
        }
        assertTrue(_timeout.schedule(10000, TimeUnit.MILLISECONDS));
        Thread.sleep(1500);
        assertFalse(_expired);
    }

    @Test
    public void testMultiple() throws Exception
    {
        Thread.sleep(1500);
        assertTrue(_expired);
        _expired = false;
        assertFalse(_timeout.schedule(500, TimeUnit.MILLISECONDS));
        Thread.sleep(1000);
        assertTrue(_expired);
        _expired = false;
        _timeout.schedule(500, TimeUnit.MILLISECONDS);
        Thread.sleep(1000);
        assertTrue(_expired);
    }

    @Test
    @Disabled
    public void testBusy() throws Exception
    {
        QueuedThreadPool pool = new QueuedThreadPool(200);
        pool.start();

        long testUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);

        assertTrue(_timeout.schedule(100, TimeUnit.MILLISECONDS));
        while (System.nanoTime() < testUntil)
        {
            CountDownLatch latch = new CountDownLatch(1);
            pool.execute(() ->
            {
                _timeout.schedule(100, TimeUnit.MILLISECONDS);
                latch.countDown();
            });
            latch.await();
        }

        assertFalse(_expired);
        pool.stop();
    }
}
