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

import java.util.HashSet;
import java.util.ListIterator;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AdvancedRunner.class)
public class BlockingArrayQueueTest
{
    @Test
    public void testWrap() throws Exception
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(3);

        Assert.assertEquals(0, queue.size());

        for (int i=0;i<queue.getMaxCapacity();i++)
        {
            queue.offer("one");
            Assert.assertEquals(1, queue.size());

            queue.offer("two");
            Assert.assertEquals(2, queue.size());

            queue.offer("three");
            Assert.assertEquals(3, queue.size());

            Assert.assertEquals("one", queue.get(0));
            Assert.assertEquals("two", queue.get(1));
            Assert.assertEquals("three", queue.get(2));

            Assert.assertEquals("[one, two, three]", queue.toString());

            Assert.assertEquals("one", queue.poll());
            Assert.assertEquals(2, queue.size());

            Assert.assertEquals("two", queue.poll());
            Assert.assertEquals(1, queue.size());

            Assert.assertEquals("three", queue.poll());
            Assert.assertEquals(0, queue.size());


            queue.offer("xxx");
            Assert.assertEquals(1, queue.size());
            Assert.assertEquals("xxx", queue.poll());
            Assert.assertEquals(0, queue.size());
        }
    }

    @Test
    public void testRemove() throws Exception
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(3,3);

        queue.add("0");
        queue.add("x");

        for (int i=1;i<100;i++)
        {
            queue.add(""+i);
            queue.add("x");
            queue.remove(queue.size()-3);
            queue.set(queue.size()-3,queue.get(queue.size()-3)+"!");
        }

        for (int i=0;i<99;i++)
            Assert.assertEquals(i + "!", queue.get(i));
    }

    @Test
    public void testLimit() throws Exception
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(1,0,1);

        String element = "0";
        Assert.assertTrue(queue.add(element));
        Assert.assertFalse(queue.offer("1"));

        Assert.assertEquals(element, queue.poll());
        Assert.assertTrue(queue.add(element));
    }

    @Test
    public void testGrow() throws Exception
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(3,2);
        Assert.assertEquals(3, queue.getCapacity());

        queue.add("a");
        queue.add("a");
        Assert.assertEquals(2, queue.size());
        Assert.assertEquals(3, queue.getCapacity());
        queue.add("a");
        queue.add("a");
        Assert.assertEquals(4, queue.size());
        Assert.assertEquals(5, queue.getCapacity());

        int s=5;
        int c=5;
        queue.add("a");

        for (int t=0;t<100;t++)
        {
            Assert.assertEquals(s, queue.size());
            Assert.assertEquals(c, queue.getCapacity());

            for (int i=queue.size();i-->0;)
                queue.poll();
            Assert.assertEquals(0, queue.size());
            Assert.assertEquals(c, queue.getCapacity());

            for (int i=queue.getCapacity();i-->0;)
                queue.add("a");
            queue.add("a");
            Assert.assertEquals(s + 1, queue.size());
            Assert.assertEquals(c + 2, queue.getCapacity());

            queue.poll();
            queue.add("a");
            queue.add("a");
            Assert.assertEquals(s + 2, queue.size());
            Assert.assertEquals(c + 2, queue.getCapacity());

            s+=2;
            c+=2;
        }
    }

    @Test
    @Slow
    public void testTake() throws Exception
    {
        final String[] data=new String[4];

        final BlockingArrayQueue<String> queue = new BlockingArrayQueue<>();

        Thread thread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    data[0]=queue.take();
                    data[1]=queue.take();
                    Thread.sleep(1000);
                    data[2]=queue.take();
                    data[3]=queue.poll(100,TimeUnit.MILLISECONDS);
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                    Assert.fail();
                }
            }
        };

        thread.start();

        Thread.sleep(1000);

        queue.offer("zero");
        queue.offer("one");
        queue.offer("two");
        thread.join();

        Assert.assertEquals("zero", data[0]);
        Assert.assertEquals("one", data[1]);
        Assert.assertEquals("two", data[2]);
        Assert.assertEquals(null, data[3]);
    }

    @Test
    @Slow
    public void testConcurrentAccess() throws Exception
    {
        final int THREADS=50;
        final int LOOPS=1000;

        final BlockingArrayQueue<Integer> queue = new BlockingArrayQueue<>(1+THREADS*LOOPS);

        final ConcurrentLinkedQueue<Integer> produced=new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Integer> consumed=new ConcurrentLinkedQueue<>();

        final AtomicBoolean running = new AtomicBoolean(true);

        // start consumers
        final CyclicBarrier barrier0 = new CyclicBarrier(THREADS+1);
        for (int i=0;i<THREADS;i++)
        {
            new Thread()
            {
                @Override
                public void run()
                {
                    final Random random = new Random();

                    setPriority(getPriority()-1);
                    try
                    {
                        while(running.get())
                        {
                            int r=1+random.nextInt(10);
                            if (r%2==0)
                            {
                                Integer msg=queue.poll();
                                if (msg==null)
                                {
                                    Thread.sleep(1+random.nextInt(10));
                                    continue;
                                }
                                consumed.add(msg);
                            }
                            else
                            {
                                Integer msg=queue.poll(r,TimeUnit.MILLISECONDS);
                                if (msg!=null)
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
                            barrier0.await();
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
        final CyclicBarrier barrier1 = new CyclicBarrier(THREADS+1);
        for (int i=0;i<THREADS;i++)
        {
            final int id = i;
            new Thread()
            {
                @Override
                public void run()
                {
                    final Random random = new Random();
                    try
                    {
                        for (int j=0;j<LOOPS;j++)
                        {
                            Integer msg = random.nextInt();
                            produced.add(msg);
                            if (!queue.offer(msg))
                                throw new Exception(id+" FULL! "+queue.size());
                            Thread.sleep(1+random.nextInt(10));
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
                            barrier1.await();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                }
            }.start();
        }

        barrier1.await();
        int size=queue.size();
        int last=size-1;
        while (size>0 && size!=last)
        {
            last=size;
            Thread.sleep(500);
            size=queue.size();
        }
        running.set(false);
        barrier0.await();

        HashSet<Integer> prodSet = new HashSet<>(produced);
        HashSet<Integer> consSet = new HashSet<>(consumed);

        Assert.assertEquals(prodSet, consSet);
    }

    @Test
    public void testRemoveObjectFromEmptyQueue()
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(4,0,4);
        Assert.assertFalse(queue.remove("SOMETHING"));
    }

    @Test
    public void testRemoveObjectWithWrappedTail() throws Exception
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(6);
        // Wrap the tail
        for (int i = 0; i < queue.getMaxCapacity(); ++i)
            queue.offer("" + i);
        // Advance the head
        queue.poll();
        // Remove from the middle
        Assert.assertTrue(queue.remove("2"));

        // Advance the tail
        Assert.assertTrue(queue.offer("A"));
        Assert.assertTrue(queue.offer("B"));
        queue.poll();
        // Remove from the middle
        Assert.assertTrue(queue.remove("3"));
    }

    @Test
    public void testRemoveObject() throws Exception
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(4,0,4);

        String element1 = "A";
        Assert.assertTrue(queue.offer(element1));
        Assert.assertTrue(queue.remove(element1));

        for (int i = 0; i < queue.getMaxCapacity() - 1; ++i)
        {
            queue.offer("" + i);
            queue.poll();
        }
        String element2 = "B";
        Assert.assertTrue(queue.offer(element2));
        Assert.assertTrue(queue.offer(element1));
        Assert.assertTrue(queue.remove(element1));

        Assert.assertFalse(queue.remove("NOT_PRESENT"));

        Assert.assertTrue(queue.remove(element2));
        Assert.assertFalse(queue.remove("NOT_PRESENT"));

        queue.clear();

        for (int i = 0; i < queue.getMaxCapacity(); ++i)
            queue.offer("" + i);

        Assert.assertTrue(queue.remove("" + (queue.getMaxCapacity() - 1)));
    }

    @Test
    public void testRemoveWithMaxCapacityOne() throws Exception
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(1);

        String element = "A";
        Assert.assertTrue(queue.offer(element));
        Assert.assertTrue(queue.remove(element));

        Assert.assertTrue(queue.offer(element));
        Assert.assertEquals(element, queue.remove(0));
    }

    @Test
    public void testIteratorWithModification() throws Exception
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(4,0,4);
        int count = queue.getMaxCapacity() - 1;
        for (int i = 0; i < count; ++i)
            queue.offer("" + i);

        int sum = 0;
        for (String element : queue)
        {
            ++sum;
            // Concurrent modification, must not change the iterator
            queue.remove(element);
        }

        Assert.assertEquals(count, sum);
        Assert.assertTrue(queue.isEmpty());
    }

    @Test
    public void testListIterator() throws Exception
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(4,0,4);
        String element1 = "A";
        String element2 = "B";
        queue.offer(element1);
        queue.offer(element2);

        ListIterator<String> iterator = queue.listIterator();
        Assert.assertTrue(iterator.hasNext());
        Assert.assertFalse(iterator.hasPrevious());

        String element = iterator.next();
        Assert.assertEquals(element1, element);
        Assert.assertTrue(iterator.hasNext());
        Assert.assertTrue(iterator.hasPrevious());

        element = iterator.next();
        Assert.assertEquals(element2, element);
        Assert.assertFalse(iterator.hasNext());
        Assert.assertTrue(iterator.hasPrevious());

        element = iterator.previous();
        Assert.assertEquals(element2, element);
        Assert.assertTrue(iterator.hasNext());
        Assert.assertTrue(iterator.hasPrevious());

        element = iterator.previous();
        Assert.assertEquals(element1, element);
        Assert.assertTrue(iterator.hasNext());
        Assert.assertFalse(iterator.hasPrevious());
    }

    @Test
    public void testListIteratorWithWrappedHead() throws Exception
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<>(4,0,4);
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
        Assert.assertTrue(iterator.hasNext());
        Assert.assertFalse(iterator.hasPrevious());

        String element = iterator.next();
        Assert.assertEquals(element1, element);
        Assert.assertTrue(iterator.hasNext());
        Assert.assertTrue(iterator.hasPrevious());

        element = iterator.next();
        Assert.assertEquals(element2, element);
        Assert.assertFalse(iterator.hasNext());
        Assert.assertTrue(iterator.hasPrevious());

        element = iterator.previous();
        Assert.assertEquals(element2, element);
        Assert.assertTrue(iterator.hasNext());
        Assert.assertTrue(iterator.hasPrevious());

        element = iterator.previous();
        Assert.assertEquals(element1, element);
        Assert.assertTrue(iterator.hasNext());
        Assert.assertFalse(iterator.hasPrevious());
    }
}
