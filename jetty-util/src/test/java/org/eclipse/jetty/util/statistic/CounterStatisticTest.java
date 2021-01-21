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

package org.eclipse.jetty.util.statistic;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

public class CounterStatisticTest
{

    @Test
    public void testCounter()
        throws Exception
    {
        CounterStatistic count = new CounterStatistic();

        assertThat(count.getCurrent(), equalTo(0L));
        assertThat(count.getMax(), equalTo(0L));
        assertThat(count.getTotal(), equalTo(0L));

        count.increment();
        count.increment();
        count.decrement();
        count.add(4);
        count.add(-2);

        assertThat(count.getCurrent(), equalTo(3L));
        assertThat(count.getMax(), equalTo(5L));
        assertThat(count.getTotal(), equalTo(6L));

        count.reset();
        assertThat(count.getCurrent(), equalTo(3L));
        assertThat(count.getMax(), equalTo(3L));
        assertThat(count.getTotal(), equalTo(3L));

        count.increment();
        count.decrement();
        count.add(-2);
        count.decrement();
        assertThat(count.getCurrent(), equalTo(0L));
        assertThat(count.getMax(), equalTo(4L));
        assertThat(count.getTotal(), equalTo(4L));

        count.decrement();
        assertThat(count.getCurrent(), equalTo(-1L));
        assertThat(count.getMax(), equalTo(4L));
        assertThat(count.getTotal(), equalTo(4L));

        count.increment();
        assertThat(count.getCurrent(), equalTo(0L));
        assertThat(count.getMax(), equalTo(4L));
        assertThat(count.getTotal(), equalTo(5L));
    }

    @Test
    public void testCounterContended()
        throws Exception
    {
        final CounterStatistic counter = new CounterStatistic();
        final int N = 100;
        final int L = 1000;
        final Thread[] threads = new Thread[N];
        final CyclicBarrier incBarrier = new CyclicBarrier(N);
        final CountDownLatch decBarrier = new CountDownLatch(N / 2);

        for (int i = N; i-- > 0; )
        {
            threads[i] = (i >= N / 2)
                ? new Thread(() ->
                {
                    try
                    {
                        incBarrier.await();
                        decBarrier.await();
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                    Random random = new Random();
                    for (int l = L; l-- > 0; )
                    {
                        counter.decrement();
                        if (random.nextInt(5) == 0)
                            Thread.yield();
                    }
                })

                : new Thread(() ->
                {
                    try
                    {
                        incBarrier.await();
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                    Random random = new Random();
                    for (int l = L; l-- > 0; )
                    {
                        counter.increment();
                        if (l == L / 2)
                            decBarrier.countDown();
                        if (random.nextInt(5) == 0)
                            Thread.yield();
                    }
                });
            threads[i].start();
        }

        for (int i = N; i-- > 0; )
        {
            threads[i].join();
        }

        assertThat(counter.getCurrent(), equalTo(0L));
        assertThat(counter.getTotal(), equalTo(N * L / 2L));
        assertThat(counter.getMax(), greaterThanOrEqualTo((N / 2) * (L / 2L)));
    }
}
