//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class BlockingArrayQueueTest
{
    @Test
    public void testWrap()
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(3);

        assertEquals(0, queue.size());

        for (int i = 0; i < queue.getMaxCapacity(); i++)
        {
            queue.offer("one");
            assertEquals(1, queue.size());

            queue.offer("two");
            assertEquals(2, queue.size());

            queue.offer("three");
            assertEquals(3, queue.size());

            assertEquals("one", queue.get(0));
            assertEquals("two", queue.get(1));
            assertEquals("three", queue.get(2));

            assertEquals("[one, two, three]", queue.toString());

            assertEquals("one", queue.poll());
            assertEquals(2, queue.size());

            assertEquals("two", queue.poll());
            assertEquals(1, queue.size());

            assertEquals("three", queue.poll());
            assertEquals(0, queue.size());

            queue.offer("xxx");
            assertEquals(1, queue.size());
            assertEquals("xxx", queue.poll());
            assertEquals(0, queue.size());
        }
    }

    @Test
    public void testRemove()
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(3, 3);

        queue.add("0");
        queue.add("x");

        for (int i = 1; i < 100; i++)
        {
            queue.add("" + i);
            queue.add("x");
            queue.remove(queue.size() - 3);
            queue.set(queue.size() - 3, queue.get(queue.size() - 3) + "!");
        }

        for (int i = 0; i < 99; i++)
        {
            assertEquals(i + "!", queue.get(i));
        }
    }

    @Test
    public void testLimit()
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(1, 0, 1);

        String element = "0";
        assertTrue(queue.add(element));
        assertFalse(queue.offer("1"));

        assertEquals(element, queue.poll());
        assertTrue(queue.add(element));
    }

    @Test
    public void testGrow()
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(3, 2);
        assertEquals(3, queue.getCapacity());

        queue.add("a");
        queue.add("a");
        assertEquals(2, queue.size());
        assertEquals(3, queue.getCapacity());
        queue.add("a");
        queue.add("a");
        assertEquals(4, queue.size());
        assertEquals(5, queue.getCapacity());

        int s = 5;
        int c = 5;
        queue.add("a");

        for (int t = 0; t < 100; t++)
        {
            assertEquals(s, queue.size());
            assertEquals(c, queue.getCapacity());

            for (int i = queue.size(); i-- > 0; )
            {
                queue.poll();
            }
            assertEquals(0, queue.size());
            assertEquals(c, queue.getCapacity());

            for (int i = queue.getCapacity(); i-- > 0; )
            {
                queue.add("a");
            }
            queue.add("a");
            assertEquals(s + 1, queue.size());
            assertEquals(c + 2, queue.getCapacity());

            queue.poll();
            queue.add("a");
            queue.add("a");
            assertEquals(s + 2, queue.size());
            assertEquals(c + 2, queue.getCapacity());

            s += 2;
            c += 2;
        }
    }

    @Test
    public void testTake() throws Exception
    {
        final String[] data = new String[4];

        final BlockingArrayQueue<String> queue = new BlockingArrayQueue<>();
        CyclicBarrier barrier = new CyclicBarrier(2);

        Thread thread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    data[0] = queue.take();
                    data[1] = queue.take();
                    barrier.await(5, TimeUnit.SECONDS); // Wait until the main thread already called offer().
                    data[2] = queue.take();
                    data[3] = queue.poll(100, TimeUnit.MILLISECONDS);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    fail("Should not had failed");
                }
            }
        };

        thread.start();

        // Wait until the spawned thread is blocked in queue.take().
        await().atMost(5, TimeUnit.SECONDS).until(() -> thread.getState() == Thread.State.WAITING);

        queue.offer("zero");
        queue.offer("one");
        queue.offer("two");
        barrier.await(5, TimeUnit.SECONDS); // Notify the spawned thread that offer() was called.
        thread.join();

        assertEquals("zero", data[0]);
        assertEquals("one", data[1]);
        assertEquals("two", data[2]);
        assertNull(data[3]);
    }

    @Test
    public void testConcurrentAccess() throws Exception
    {
        final int THREADS = 32;
        final int LOOPS = 1000;

        BlockingArrayQueue<Integer> queue = new BlockingArrayQueue<>(1 + THREADS * LOOPS);

        Set<Integer> produced = ConcurrentHashMap.newKeySet();
        Set<Integer> consumed = ConcurrentHashMap.newKeySet();

        AtomicBoolean consumersRunning = new AtomicBoolean(true);

        // start consumers
        CyclicBarrier consumersBarrier = new CyclicBarrier(THREADS + 1);
        for (int i = 0; i < THREADS; i++)
        {
            new Thread()
            {
                @Override
                public void run()
                {
                    setPriority(getPriority() - 1);
                    try
                    {
                        while (consumersRunning.get())
                        {
                            int r = 1 + ThreadLocalRandom.current().nextInt(10);
                            if (r % 2 == 0)
                            {
                                Integer msg = queue.poll();
                                if (msg == null)
                                {
                                    Thread.sleep(ThreadLocalRandom.current().nextInt(2));
                                    continue;
                                }
                                consumed.add(msg);
                            }
                            else
                            {
                                Integer msg = queue.poll(r, TimeUnit.MILLISECONDS);
                                if (msg != null)
                                    consumed.add(msg);
                            }
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                    finally
                    {
                        try
                        {
                            consumersBarrier.await();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                }
            }.start();
        }

        // start producers
        CyclicBarrier producersBarrier = new CyclicBarrier(THREADS + 1);
        for (int i = 0; i < THREADS; i++)
        {
            final int id = i;
            new Thread()
            {
                @Override
                public void run()
                {
                    try
                    {
                        for (int j = 0; j < LOOPS; j++)
                        {
                            Integer msg = ThreadLocalRandom.current().nextInt();
                            produced.add(msg);
                            if (!queue.offer(msg))
                                throw new Exception(id + " FULL! " + queue.size());
                            Thread.sleep(ThreadLocalRandom.current().nextInt(2));
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                    finally
                    {
                        try
                        {
                            producersBarrier.await();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                }
            }.start();
        }

        producersBarrier.await();

        AtomicInteger last = new AtomicInteger(queue.size() - 1);
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            int size = queue.size();
            if (size == 0 && last.get() == size)
                return true;
            last.set(size);
            return false;
        });

        consumersRunning.set(false);
        consumersBarrier.await();

        assertEquals(produced, consumed);
    }

    @Test
    public void testRemoveObjectFromEmptyQueue()
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(4, 0, 4);
        assertFalse(queue.remove("SOMETHING"));
    }

    @Test
    public void testRemoveObjectWithWrappedTail()
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(6);
        // Wrap the tail
        for (int i = 0; i < queue.getMaxCapacity(); ++i)
        {
            queue.offer("" + i);
        }
        // Advance the head
        queue.poll();
        // Remove from the middle
        assertTrue(queue.remove("2"));

        // Advance the tail
        assertTrue(queue.offer("A"));
        assertTrue(queue.offer("B"));
        queue.poll();
        // Remove from the middle
        assertTrue(queue.remove("3"));
    }

    @Test
    public void testRemoveObject()
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(4, 0, 4);

        String element1 = "A";
        assertTrue(queue.offer(element1));
        assertTrue(queue.remove(element1));

        for (int i = 0; i < queue.getMaxCapacity() - 1; ++i)
        {
            queue.offer("" + i);
            queue.poll();
        }
        String element2 = "B";
        assertTrue(queue.offer(element2));
        assertTrue(queue.offer(element1));
        assertTrue(queue.remove(element1));

        assertFalse(queue.remove("NOT_PRESENT"));

        assertTrue(queue.remove(element2));
        assertFalse(queue.remove("NOT_PRESENT"));

        queue.clear();

        for (int i = 0; i < queue.getMaxCapacity(); ++i)
        {
            queue.offer("" + i);
        }

        assertTrue(queue.remove("" + (queue.getMaxCapacity() - 1)));
    }

    @Test
    public void testRemoveWithMaxCapacityOne()
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(1);

        String element = "A";
        assertTrue(queue.offer(element));
        assertTrue(queue.remove(element));

        assertTrue(queue.offer(element));
        assertEquals(element, queue.remove(0));
    }

    @Test
    public void testIteratorWithModification()
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(4, 0, 4);
        int count = queue.getMaxCapacity() - 1;
        for (int i = 0; i < count; ++i)
        {
            queue.offer("" + i);
        }

        int sum = 0;
        for (String element : queue)
        {
            ++sum;
            // Concurrent modification, must not change the iterator
            queue.remove(element);
        }

        assertEquals(count, sum);
        assertTrue(queue.isEmpty());
    }

    @Test
    public void testListIterator()
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(4, 0, 4);
        String element1 = "A";
        String element2 = "B";
        queue.offer(element1);
        queue.offer(element2);

        ListIterator<String> iterator = queue.listIterator();
        assertTrue(iterator.hasNext());
        assertFalse(iterator.hasPrevious());

        String element = iterator.next();
        assertEquals(element1, element);
        assertTrue(iterator.hasNext());
        assertTrue(iterator.hasPrevious());

        element = iterator.next();
        assertEquals(element2, element);
        assertFalse(iterator.hasNext());
        assertTrue(iterator.hasPrevious());

        element = iterator.previous();
        assertEquals(element2, element);
        assertTrue(iterator.hasNext());
        assertTrue(iterator.hasPrevious());

        element = iterator.previous();
        assertEquals(element1, element);
        assertTrue(iterator.hasNext());
        assertFalse(iterator.hasPrevious());
    }

    @Test
    public void testListIteratorWithWrappedHead()
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(4, 0, 4);
        // This sequence of offers and polls wraps the head around the array
        queue.offer("0");
        queue.offer("1");
        queue.offer("2");
        queue.offer("3");
        queue.poll();
        queue.poll();

        String element1 = queue.get(0);
        String element2 = queue.get(1);

        ListIterator<String> iterator = queue.listIterator();
        assertTrue(iterator.hasNext());
        assertFalse(iterator.hasPrevious());

        String element = iterator.next();
        assertEquals(element1, element);
        assertTrue(iterator.hasNext());
        assertTrue(iterator.hasPrevious());

        element = iterator.next();
        assertEquals(element2, element);
        assertFalse(iterator.hasNext());
        assertTrue(iterator.hasPrevious());

        element = iterator.previous();
        assertEquals(element2, element);
        assertTrue(iterator.hasNext());
        assertTrue(iterator.hasPrevious());

        element = iterator.previous();
        assertEquals(element1, element);
        assertTrue(iterator.hasNext());
        assertFalse(iterator.hasPrevious());
    }

    @Test
    public void testDrainTo()
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>();
        queue.add("one");
        queue.add("two");
        queue.add("three");
        queue.add("four");
        queue.add("five");
        queue.add("six");

        List<String> to = new ArrayList<>();
        queue.drainTo(to, 3);
        assertThat(to, Matchers.contains("one", "two", "three"));
        assertThat(queue.size(), Matchers.is(3));
        assertThat(queue, Matchers.contains("four", "five", "six"));

        queue.drainTo(to);
        assertThat(to, Matchers.contains("one", "two", "three", "four", "five", "six"));
        assertThat(queue.size(), Matchers.is(0));
        assertThat(queue, Matchers.empty());
    }

    @Test
    public void testDrainToAtDefaultGrowthSize()
    {
        BlockingArrayQueue<Integer> queue = new BlockingArrayQueue<>();
        for (int i = 0; i < BlockingArrayQueue.DEFAULT_GROWTH * 2; i++)
        {
            queue.add(i);
        }

        List<Integer> list = new ArrayList<>();
        assertThat(queue.drainTo(list), is(BlockingArrayQueue.DEFAULT_GROWTH * 2));
        assertThat(list.size(), is(BlockingArrayQueue.DEFAULT_GROWTH * 2));
        assertThat(queue.size(), is(0));
    }

    @Test
    public void testDrainToAtZeroSize()
    {
        BlockingArrayQueue<Integer> queue = new BlockingArrayQueue<>();

        List<Integer> list = new ArrayList<>();
        assertThat(queue.drainTo(list), is(0));
        assertThat(list.size(), is(0));
        assertThat(queue.size(), is(0));
    }
}
