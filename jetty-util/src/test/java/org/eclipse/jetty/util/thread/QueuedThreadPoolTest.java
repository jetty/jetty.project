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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;


public class QueuedThreadPoolTest
{
    final AtomicInteger _jobs=new AtomicInteger();
    volatile long _sleep=100;
    
    class RunningJob implements Runnable
    {
        public volatile boolean _running=true;
        public void run()
        {
            try 
            {
                while(_running)
                    Thread.sleep(_sleep);
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
      
            _jobs.incrementAndGet();
        }
    };   
    
    
    
    @Test
    public void testThreadPool() throws Exception
    {        
        _sleep=100;
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
        
        RunningJob job=new RunningJob();
        tp.dispatch(job);
        Thread.sleep(200);
        assertEquals(5,tp.getThreads());
        assertEquals(4,tp.getIdleThreads());
        job._running=false;
        Thread.sleep(200);
        assertEquals(5,tp.getThreads());
        assertEquals(5,tp.getIdleThreads());

        RunningJob[] jobs = new RunningJob[5];
        for (int i=0;i<jobs.length;i++)
        {
            jobs[i]=new RunningJob();
            tp.dispatch(jobs[i]);
        }
        Thread.sleep(200);
        assertEquals(5,tp.getThreads());
        Thread.sleep(1000);
        assertEquals(5,tp.getThreads());
        
        job=new RunningJob();
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
        
        
        jobs = new RunningJob[15];
        for (int i=0;i<jobs.length;i++)
        {
            jobs[i]=new RunningJob();
            tp.dispatch(jobs[i]);
        }
        assertEquals(10,tp.getThreads());
        Thread.sleep(100);
        assertEquals(0,tp.getIdleThreads());

        for (int i=0;i<9;i++)
            jobs[i]._running=false;
        Thread.sleep(1100);
        int threads=tp.getThreads();
        assertTrue(threads<10);
        Thread.sleep(1100);
        assertTrue(tp.getThreads()<threads);
        
        for (int i=9;i<jobs.length;i++)
            jobs[i]._running=false;
        Thread.sleep(500);

        tp.stop();
    }

    @Test
    public void testShrink() throws Exception
    {
        Runnable job = new Runnable()
        {
            public void run()
            {
                try 
                {
                    Thread.sleep(_sleep);
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
            
        };
        
        QueuedThreadPool tp= new QueuedThreadPool();
        tp.setMinThreads(2);
        tp.setMaxThreads(10);
        tp.setMaxIdleTimeMs(400);
        tp.setThreadsPriority(Thread.NORM_PRIORITY-1);
        
        tp.start();
        Thread.sleep(100);
        assertEquals(2,tp.getThreads());
        assertEquals(2,tp.getIdleThreads());
        _sleep=200;
        tp.dispatch(job);
        tp.dispatch(job);
        for (int i=0;i<20;i++)
            tp.dispatch(job);
        Thread.sleep(100);
        assertEquals(10,tp.getThreads());
        assertEquals(0,tp.getIdleThreads());
        
        _sleep=5;
        for (int i=0;i<500;i++)
        {
            tp.dispatch(job);
            Thread.sleep(10);
            if (i%100==0)
            {
                System.err.println(i+" threads="+tp.getThreads()+" idle="+tp.getIdleThreads());
            }
        }
        System.err.println("500 threads="+tp.getThreads()+" idle="+tp.getIdleThreads());
        Thread.sleep(100);
        System.err.println("600 threads="+tp.getThreads()+" idle="+tp.getIdleThreads());
        assertEquals(2,tp.getThreads());
        assertEquals(2,tp.getIdleThreads());
        
    }

    @Test
    public void testMaxStopTime() throws Exception
    {
        _sleep=100;
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
