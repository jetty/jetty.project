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

package org.eclipse.jetty.util.thread;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.junit.Before;
import org.junit.Test;


public class TimeoutTest
{
	private boolean _stress=Boolean.getBoolean("STRESS");
	
    Object lock = new Object();
    Timeout timeout = new Timeout(null);
    Timeout.Task[] tasks;

    /* ------------------------------------------------------------ */
    /* 
     * @see junit.framework.TestCase#setUp()
     */
    @Before
    public void setUp() throws Exception
    {
        timeout=new Timeout(lock);
        tasks= new Timeout.Task[10]; 
        
        for (int i=0;i<tasks.length;i++)
        {
            tasks[i]=new Timeout.Task();
            timeout.setNow(1000+i*100);
            timeout.schedule(tasks[i]);
        }
        timeout.setNow(100);
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testExpiry()
    {
        timeout.setDuration(200);
        timeout.setNow(1500);
        timeout.tick();
        
        for (int i=0;i<tasks.length;i++)
        {
            assertEquals("isExpired "+i,i<4, tasks[i].isExpired());
        }
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testCancel()
    {
        timeout.setDuration(200);
        timeout.setNow(1700);

        for (int i=0;i<tasks.length;i++)
            if (i%2==1)
                tasks[i].cancel();

        timeout.tick();
        
        for (int i=0;i<tasks.length;i++)
        {
            assertEquals("isExpired "+i,i%2==0 && i<6, tasks[i].isExpired());
        }
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testTouch()
    {
        timeout.setDuration(200);
        timeout.setNow(1350);
        timeout.schedule(tasks[2]);
        
        timeout.setNow(1500);
        timeout.tick();
        for (int i=0;i<tasks.length;i++)
        {
            assertEquals("isExpired "+i,i!=2 && i<4, tasks[i].isExpired());
        }
        
        timeout.setNow(1550);
        timeout.tick();
        for (int i=0;i<tasks.length;i++)
        {
            assertEquals("isExpired "+i, i<4, tasks[i].isExpired());
        }  
    }


    /* ------------------------------------------------------------ */
    @Test
    public void testDelay()
    {
        Timeout.Task task = new Timeout.Task();

        timeout.setNow(1100);
        timeout.schedule(task, 300);
        timeout.setDuration(200);
        
        timeout.setNow(1300);
        timeout.tick();
        assertEquals("delay", false, task.isExpired());
        
        timeout.setNow(1500);
        timeout.tick();
        assertEquals("delay", false, task.isExpired());
        
        timeout.setNow(1700);
        timeout.tick();
        assertEquals("delay", true, task.isExpired());
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testStress() throws Exception
    {
    	if ( !_stress )
    		return;
    	
        final int LOOP=250;
        final AtomicBoolean running=new AtomicBoolean(true);
        final AtomicIntegerArray count = new AtomicIntegerArray( 4 );


        timeout.setNow(System.currentTimeMillis());
        timeout.setDuration(500);
        
        // Start a ticker thread that will tick over the timer frequently.
        Thread ticker = new Thread()
        {
            @Override
            public void run()
            {
                while (running.get())
                {
                    try
                    {
                        // use lock.wait so we have a memory barrier and
                        // have no funny optimisation issues.
                        synchronized (lock)
                        {
                            lock.wait(30);
                        }
                        Thread.sleep(30);
                        timeout.tick(System.currentTimeMillis());
                    }
                    catch(Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        };
        ticker.start();

        // start lots of test threads
        for (int i=0;i<LOOP;i++)
        {
            // 
            Thread th = new Thread()
            { 
                @Override
                public void run()
                {
                    // count how many threads were started (should == LOOP)
                    int once = (int) 10 + count.incrementAndGet( 0 )%50;
                    
                    // create a task for this thread
                    Timeout.Task task = new Timeout.Task()
                    {
                        @Override
                        public void expired()
                        {       
                            // count the number of expires                           
                            count.incrementAndGet( 2 );                          
                        }
                    };
                    
                    // this thread will loop and each loop with schedule a 
                    // task with a delay  on top of the timeouts duration
                    // mostly this thread will then cancel the task
                    // But once it will wait and the task will expire
                    
                    
                    // do the looping until we are stopped
                    int loop=0;
                    while (running.get())
                    {
                        try
                        {
                            long delay=1000;
                            long wait=100-once;
                            
                            if (loop++==once)
                            { 
                                // THIS loop is the one time we wait longer than the delay
                                count.incrementAndGet( 1 );  
                                delay=200;
                                wait=1000;
                            }
                            
                            timeout.schedule(task,delay);
                            
                            // do the wait
                            Thread.sleep(wait);
                            
                            // cancel task (which may have expired)
                            task.cancel();
                        }
                        catch(Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                    count.incrementAndGet(3);
                }
            };
            th.start();
        }
        
        long start=System.currentTimeMillis();
        
        // run test until all threads are started
        while (count.get(0)<LOOP && (System.currentTimeMillis()-start)<20000)
            Thread.sleep(50);
        // run test until all expires initiated
        while (count.get(1)<LOOP && (System.currentTimeMillis()-start)<20000)
            Thread.sleep(50);
        
        // run test until all expires initiated
        while (count.get(2)<LOOP && (System.currentTimeMillis()-start)<20000)
            Thread.sleep(50);
        
        running.set(false);

        // run test until all threads complete
        while (count.get(3)<LOOP && (System.currentTimeMillis()-start)<20000)
            Thread.sleep(50);
        
        // check the counts
        assertEquals("count threads", LOOP,count.get( 0 ));
        assertEquals("count once waits",LOOP,count.get(1 ));
        assertEquals("count expires",LOOP,count.get(2));
        assertEquals("done",LOOP,count.get(3));
    }
}
