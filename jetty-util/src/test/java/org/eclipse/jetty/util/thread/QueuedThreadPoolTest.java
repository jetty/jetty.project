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


package org.eclipse.jetty.util.thread;

import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.TestCase;

public class QueuedThreadPoolTest extends TestCase
{
    final AtomicInteger _jobs=new AtomicInteger();
    
    class Job implements Runnable
    {
        public volatile boolean _running=true;
        public void run()
        {
            try 
            {
                while(_running)
                    Thread.sleep(100);
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
      
            _jobs.incrementAndGet();
        }
    };    
    
    public void testThreadPool() throws Exception
    {        
        QueuedThreadPool tp= new QueuedThreadPool();
        tp.setMinThreads(5);
        tp.setMaxThreads(10);
        tp.setMaxIdleTimeMs(500);
        tp.setThreadsPriority(Thread.NORM_PRIORITY-1);

        tp.start();
        Thread.sleep(100);
        assertEquals(5,tp.getThreads());
        assertEquals(5,tp.getIdleThreads());
        Thread.sleep(1000);
        assertEquals(5,tp.getThreads());
        assertEquals(5,tp.getIdleThreads());
        
        Job job=new Job();
        tp.dispatch(job);
        Thread.sleep(200);
        assertEquals(5,tp.getThreads());
        assertEquals(4,tp.getIdleThreads());
        job._running=false;
        Thread.sleep(200);
        assertEquals(5,tp.getThreads());
        assertEquals(5,tp.getIdleThreads());

        Job[] jobs = new Job[5];
        for (int i=0;i<jobs.length;i++)
        {
            jobs[i]=new Job();
            tp.dispatch(jobs[i]);
        }
        Thread.sleep(200);
        assertEquals(5,tp.getThreads());
        Thread.sleep(1000);
        assertEquals(5,tp.getThreads());
        
        job=new Job();
        tp.dispatch(job);
        assertEquals(6,tp.getThreads());
        
        job._running=false;
        Thread.sleep(1000);
        assertEquals(5,tp.getThreads());
        
        jobs[0]._running=false;
        Thread.sleep(1000);

        assertEquals(5,tp.getThreads());
        assertEquals(1,tp.getIdleThreads());
        
        for (int i=1;i<jobs.length;i++)
            jobs[i]._running=false;

        Thread.sleep(1000);

        assertEquals(5,tp.getThreads());
        
        
        jobs = new Job[15];
        for (int i=0;i<jobs.length;i++)
        {
            jobs[i]=new Job();
            tp.dispatch(jobs[i]);
        }
        assertEquals(10,tp.getThreads());
        Thread.sleep(100);
        assertEquals(0,tp.getIdleThreads());

        for (int i=0;i<9;i++)
            jobs[i]._running=false;
        Thread.sleep(1000);

        assertTrue(tp.getThreads()<10);
        int threads=tp.getThreads();
        Thread.sleep(1000);
        assertTrue(tp.getThreads()<threads);
        threads=tp.getThreads();
        Thread.sleep(1000);
        assertTrue(tp.getThreads()<threads);
        
        for (int i=9;i<jobs.length;i++)
            jobs[i]._running=false;
        Thread.sleep(1000);

        tp.stop();
    }
    

    public void testMaxStopTime() throws Exception
    {
        QueuedThreadPool tp= new QueuedThreadPool();
        tp.setMaxStopTimeMs(500);
        tp.start();
        tp.dispatch(new Runnable(){
            public void run () {
                while (true) {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ie) {}
                }
            }
        });

        long beforeStop = System.currentTimeMillis();
        tp.stop();
        long afterStop = System.currentTimeMillis();
        assertTrue(tp.isStopped());
        assertTrue(afterStop - beforeStop < 1000);
    }


}
