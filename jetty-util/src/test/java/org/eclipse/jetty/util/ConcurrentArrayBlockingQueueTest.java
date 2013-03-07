//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

public class ConcurrentArrayBlockingQueueTest extends ConcurrentArrayQueueTest
{
    @Override
    protected ConcurrentArrayBlockingQueue<Integer> newConcurrentArrayQueue(int blockSize)
    {
        return new ConcurrentArrayBlockingQueue<>(blockSize);
    }

    @Test
    public void testOfferTake() throws Exception
    {
        ConcurrentArrayBlockingQueue<Integer> queue = newConcurrentArrayQueue(32);
        Integer item = 1;
        Assert.assertTrue(queue.offer(item));
        Integer taken = queue.take();
        Assert.assertSame(item, taken);
    }

    @Test
    public void testConcurrentOfferTake() throws Exception
    {
        final ConcurrentArrayBlockingQueue<Integer> queue = newConcurrentArrayQueue(512);
        int readerCount = 16;
        final int factor = 2;
        int writerCount = readerCount * factor;
        final int iterations = 1024;
        for (int runs = 0; runs < 16; ++runs)
        {
            ExecutorService executor = Executors.newFixedThreadPool(readerCount + writerCount);
            List<Future<Integer>> readers = new ArrayList<>();
            for (int i = 0; i < readerCount / 2; ++i)
            {
                final int reader = i;
                readers.add(executor.submit(new Callable<Integer>()
                {
                    @Override
                    public Integer call() throws Exception
                    {
                        int sum = 0;
                        for (int j = 0; j < iterations * factor; ++j)
                            sum += queue.take();
                        System.err.println("Taking reader " + reader + " completed: " + sum);
                        return sum;
                    }
                }));
                readers.add(executor.submit(new Callable<Integer>()
                {
                    @Override
                    public Integer call() throws Exception
                    {
                        int sum = 0;
                        for (int j = 0; j < iterations * factor; ++j)
                            sum += queue.poll(5, TimeUnit.SECONDS);
                        System.err.println("Polling Reader " + reader + " completed: " + sum);
                        return sum;
                    }
                }));
            }
            for (int i = 0; i < writerCount; ++i)
            {
                final int writer = i;
                executor.submit(new Callable<Object>()
                {
                    @Override
                    public Object call() throws Exception
                    {
                        for (int j = 0; j < iterations; ++j)
                            queue.offer(1);
                        System.err.println("Writer " + writer + " completed");
                        return null;
                    }
                });
            }

            int sum = 0;
            for (Future<Integer> result : readers)
                sum += result.get();

            Assert.assertEquals(writerCount * iterations, sum);
            Assert.assertTrue(queue.isEmpty());
        }
    }

    @Test
    public void testDrain() throws Exception
    {
        final ConcurrentArrayBlockingQueue<Integer> queue = newConcurrentArrayQueue(512);
        List<Integer> chunk1 = Arrays.asList(1, 2);
        List<Integer> chunk2 = Arrays.asList(3, 4, 5);
        queue.addAll(chunk1);
        queue.addAll(chunk2);

        List<Integer> drainer1 = new ArrayList<>();
        queue.drainTo(drainer1, chunk1.size());
        List<Integer> drainer2 = new ArrayList<>();
        queue.drainTo(drainer2, chunk2.size());

        Assert.assertEquals(chunk1, drainer1);
        Assert.assertEquals(chunk2, drainer2);
    }
}
