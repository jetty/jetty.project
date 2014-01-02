//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.util.Iterator;
import java.util.NoSuchElementException;

import org.junit.Assert;
import org.junit.Test;

public class ConcurrentArrayQueueTest
{
    protected ConcurrentArrayQueue<Integer> newConcurrentArrayQueue(int blockSize)
    {
        return new ConcurrentArrayQueue<>(blockSize);
    }

    @Test
    public void testOfferCreatesBlock()
    {
        int blockSize = 2;
        ConcurrentArrayQueue<Integer> queue = newConcurrentArrayQueue(blockSize);
        int blocks = 3;
        for (int i = 0; i < blocks * blockSize + 1; ++i)
            queue.offer(i);
        Assert.assertEquals(blocks + 1, queue.getBlockCount());
    }

    @Test
    public void testPeekRemove() throws Exception
    {
        int blockSize = 2;
        ConcurrentArrayQueue<Integer> queue = newConcurrentArrayQueue(blockSize);

        Assert.assertNull(queue.peek());

        queue.offer(1);
        queue.remove(1);
        Assert.assertNull(queue.peek());

        int blocks = 3;
        int size = blocks * blockSize + 1;
        for (int i = 0; i < size; ++i)
            queue.offer(i);
        for (int i = 0; i < size; ++i)
        {
            Assert.assertEquals(i, (int)queue.peek());
            Assert.assertEquals(i, (int)queue.remove());
        }
    }

    @Test
    public void testRemoveObject() throws Exception
    {
        int blockSize = 2;
        ConcurrentArrayQueue<Integer> queue = newConcurrentArrayQueue(blockSize);
        queue.add(1);
        queue.add(2);
        queue.add(3);

        Assert.assertFalse(queue.remove(4));

        int size = queue.size();

        Assert.assertTrue(queue.remove(2));
        --size;
        Assert.assertEquals(size, queue.size());

        Iterator<Integer> iterator = queue.iterator();
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals(1, (int)iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals(3, (int)iterator.next());

        queue.offer(4);
        ++size;

        Assert.assertTrue(queue.remove(3));
        --size;
        Assert.assertEquals(size, queue.size());

        iterator = queue.iterator();
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals(1, (int)iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals(4, (int)iterator.next());

        Assert.assertTrue(queue.remove(1));
        --size;
        Assert.assertTrue(queue.remove(4));
        --size;

        iterator = queue.iterator();
        Assert.assertFalse(iterator.hasNext());
    }

    @Test
    public void testSize() throws Exception
    {
        int blockSize = 2;
        ConcurrentArrayQueue<Integer> queue = newConcurrentArrayQueue(blockSize);
        queue.offer(1);
        Assert.assertEquals(1, queue.size());

        queue = newConcurrentArrayQueue(blockSize);
        for (int i = 0; i < 2 * blockSize; ++i)
            queue.offer(i);
        for (int i = 0; i < blockSize; ++i)
            queue.poll();
        Assert.assertEquals(blockSize, queue.size());
    }

    @Test
    public void testIterator() throws Exception
    {
        int blockSize = 2;
        ConcurrentArrayQueue<Integer> queue = newConcurrentArrayQueue(blockSize);
        queue.offer(1);
        Iterator<Integer> iterator = queue.iterator();
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals(1, (int)iterator.next());
        Assert.assertFalse(iterator.hasNext());

        try
        {
            iterator.next();
            Assert.fail();
        }
        catch (NoSuchElementException ignored)
        {
        }

        // Test block edge
        queue = newConcurrentArrayQueue(blockSize);
        for (int i = 0; i < blockSize * 2; ++i)
            queue.offer(i);
        queue.poll();
        iterator = queue.iterator();
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals(1, (int)iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals(2, (int)iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals(3, (int)iterator.next());
        Assert.assertFalse(iterator.hasNext());

        try
        {
            iterator.next();
            Assert.fail();
        }
        catch (NoSuchElementException ignored)
        {
        }
    }
}
