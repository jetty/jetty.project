//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util.thread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SweeperTest
{
    private Scheduler scheduler;

    @BeforeEach
    public void prepare() throws Exception
    {
        scheduler = new ScheduledExecutorScheduler();
        scheduler.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        scheduler.stop();
    }

    @Test
    public void testResourceNotSweptIsNotRemoved() throws Exception
    {
        testResourceSweepRemove(false);
    }

    @Test
    public void testResourceSweptIsRemoved() throws Exception
    {
        testResourceSweepRemove(true);
    }

    private void testResourceSweepRemove(final boolean sweep) throws Exception
    {
        long period = 1000;
        final CountDownLatch taskLatch = new CountDownLatch(1);
        Sweeper sweeper = new Sweeper(scheduler, period)
        {
            @Override
            public void run()
            {
                super.run();
                taskLatch.countDown();
            }
        };
        sweeper.start();

        final CountDownLatch sweepLatch = new CountDownLatch(1);
        sweeper.offer(new Sweeper.Sweepable()
        {
            @Override
            public boolean sweep()
            {
                sweepLatch.countDown();
                return sweep;
            }
        });

        assertTrue(sweepLatch.await(2 * period, TimeUnit.MILLISECONDS));
        assertTrue(taskLatch.await(2 * period, TimeUnit.MILLISECONDS));
        assertEquals(sweep ? 0 : 1, sweeper.getSize());

        sweeper.stop();
    }

    @Test
    public void testSweepThrows() throws Exception
    {
        try (StacklessLogging scope = new StacklessLogging(Sweeper.class))
        {
            long period = 500;
            final CountDownLatch taskLatch = new CountDownLatch(2);
            Sweeper sweeper = new Sweeper(scheduler, period)
            {
                @Override
                public void run()
                {
                    super.run();
                    taskLatch.countDown();
                }
            };
            sweeper.start();

            final CountDownLatch sweepLatch = new CountDownLatch(2);
            sweeper.offer(new Sweeper.Sweepable()
            {
                @Override
                public boolean sweep()
                {
                    sweepLatch.countDown();
                    throw new NullPointerException("Test exception!");
                }
            });

            assertTrue(sweepLatch.await(4 * period, TimeUnit.MILLISECONDS));
            assertTrue(taskLatch.await(4 * period, TimeUnit.MILLISECONDS));
            assertEquals(1, sweeper.getSize());

            sweeper.stop();
        }
    }
}
