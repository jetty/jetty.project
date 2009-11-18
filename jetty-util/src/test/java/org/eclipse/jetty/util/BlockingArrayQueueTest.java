// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.util;

import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

public class BlockingArrayQueueTest extends TestCase
{
    
    public void testWrap() throws Exception
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<String>(3);
        
        assertEquals(0,queue.size());

        for (int i=0;i<3;i++)
        {
            queue.offer("one");
            assertEquals(1,queue.size());

            queue.offer("two");
            assertEquals(2,queue.size());

            queue.offer("three");
            assertEquals(3,queue.size());

            assertEquals("one",queue.get(0));
            assertEquals("two",queue.get(1));
            assertEquals("three",queue.get(2));

            assertEquals("[one, two, three]",queue.toString());

            assertEquals("one",queue.poll());
            assertEquals(2,queue.size());

            assertEquals("two",queue.poll());
            assertEquals(1,queue.size());

            assertEquals("three",queue.poll());
            assertEquals(0,queue.size());


            queue.offer("xxx");
            assertEquals(1,queue.size());
            assertEquals("xxx",queue.poll());
            assertEquals(0,queue.size());

        }

    }

    public void testRemove() throws Exception
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<String>(3,3);
       
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
            assertEquals(i+"!",queue.get(i));
    }

    public void testGrow() throws Exception
    {
        BlockingArrayQueue<String> queue = new BlockingArrayQueue<String>(3,2);
        assertEquals(3,queue.getCapacity());
        
        queue.add("a");
        queue.add("a");
        assertEquals(2,queue.size());
        assertEquals(3,queue.getCapacity());
        queue.add("a");
        queue.add("a");
        assertEquals(4,queue.size());
        assertEquals(5,queue.getCapacity());

        int s=5;
        int c=5;
        queue.add("a");
        
        for (int t=0;t<100;t++)
        {
            assertEquals(s,queue.size());
            assertEquals(c,queue.getCapacity());

            for (int i=queue.size();i-->0;)
                queue.poll();
            assertEquals(0,queue.size());
            assertEquals(c,queue.getCapacity());

            for (int i=queue.getCapacity();i-->0;)
                queue.add("a");
            queue.add("a");
            assertEquals(s+1,queue.size());
            assertEquals(c+2,queue.getCapacity());

            queue.poll();
            queue.add("a");
            queue.add("a");
            assertEquals(s+2,queue.size());
            assertEquals(c+2,queue.getCapacity());

            s+=2;
            c+=2;
        }
        
        
        
    }
    
    public void testTake() throws Exception
    {
        final String[] data=new String[4];

        final BlockingArrayQueue<String> queue = new BlockingArrayQueue<String>();
        
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
                    assertTrue(false);
                    e.printStackTrace();
                }
            }
        };
        
        thread.start();
        
        Thread.sleep(1000);

        queue.offer("zero");
        queue.offer("one");
        queue.offer("two");
        thread.join();

        assertEquals("zero",data[0]);
        assertEquals("one",data[1]);
        assertEquals("two",data[2]);
        assertEquals(null,data[3]);
        
    }
    
    volatile boolean _running;
    
    public void testConcurrentAccess() throws Exception
    {
        final int THREADS=50;
        final int LOOPS=1000;

        final BlockingArrayQueue<Integer> queue = new BlockingArrayQueue<Integer>(1+THREADS*LOOPS);
        
        final ConcurrentLinkedQueue<Integer> produced=new ConcurrentLinkedQueue<Integer>();
        final ConcurrentLinkedQueue<Integer> consumed=new ConcurrentLinkedQueue<Integer>();
        

        _running=true;
        
        // start consumers
        final CyclicBarrier barrier0 = new CyclicBarrier(THREADS+1);
        for (int i=0;i<THREADS;i++)
        {
            final Integer id = new Integer(i);
            new Thread()
            {
                @Override
                public void run()
                {
                    final Random random = new Random();
                    
                    setPriority(getPriority()-1);
                    try
                    {
                        while(_running)
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
                            // TODO Auto-generated catch block
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
            final Integer id = new Integer(i);
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
                            Integer msg = new Integer(random.nextInt());
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
                            // TODO Auto-generated catch block
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
        _running=false;
        barrier0.await();
        
        HashSet<Integer> prodSet = new HashSet<Integer>(produced);
        HashSet<Integer> consSet = new HashSet<Integer>(consumed);
        
        assertEquals(prodSet,consSet);
        
        
        
        
    }
}
