// ========================================================================
// Copyright (c) Webtide LLC
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


package org.eclipse.jetty.monitor;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;


/* ------------------------------------------------------------ */
/**
 */
public class ThreadMonitorTest
{
    public final static int DURATION=9000;
    
    @Test
    public void monitorTest() throws Exception
    {
        final AtomicInteger countSpins=new AtomicInteger(0);
        final AtomicInteger countCpuLogs=new AtomicInteger(0);
        final AtomicInteger countStateLogs=new AtomicInteger(0);
        
        ThreadMonitor monitor = new ThreadMonitor(1000,50,1)
        {
            @Override
            protected void spinAnalyzer(ThreadMonitorInfo info)
            {
                countSpins.incrementAndGet();
                super.spinAnalyzer(info);
            }
            @Override
            protected void logCpuUsage()
            {
                countCpuLogs.incrementAndGet();
                super.logCpuUsage();
            }
            @Override
            protected void logThreadState()
            {
                countStateLogs.incrementAndGet();
                super.logThreadState();
            }
        };
        monitor.logCpuUsage(2000,1);
        monitor.logSpinInfo(100,20);
        monitor.start();
        
        Spinner spinner = new Spinner();
        Thread runner = new Thread(spinner);
        runner.start();
        
        Locker locker1 = new Locker();
        Locker locker2 = new Locker();
        locker1.setLock(locker2);
        locker2.setLock(locker1);
        Thread runner1 = new Thread(locker1);
        Thread runner2 = new Thread(locker2);
        runner1.start();
        runner2.start();
        
        Thread.sleep(DURATION);
                
        spinner.setDone();
        monitor.stop();
        runner1.interrupt();
        runner2.interrupt();
        
        assertTrue(countSpins.get() >= 1);
        assertTrue(countCpuLogs.get() >= 1);
        assertTrue(countStateLogs.get() >= 1);
    }


    private class Spinner implements Runnable
    {
        private volatile boolean done = false;

        /* ------------------------------------------------------------ */
        /**
         */
        public void setDone()
        {
            done = true;
        }

        /* ------------------------------------------------------------ */
        public void run()
        {
            spin();
        }

        /* ------------------------------------------------------------ */
        public void spin()
        {
            long result=-1;
            long end=System.currentTimeMillis()+DURATION+1000;
            while (!done && System.currentTimeMillis()<end)
            {
                for (int i=0;i<1000000000;i++)
                    result^=i;
            }
            
            if (result==42)
                System.err.println("Bingo!");
        }
    }
    
    private class Locker implements Runnable
    {
        private Object _lock;
        
        public void setLock(Object lock)
        {
            _lock = lock;
        }
        
        public void run()
        {
            try
            {
                lockOn();
            }
            catch (InterruptedException ex) {}
        }
        
        public synchronized void lockOn() throws InterruptedException
        {
            Thread.sleep(100);
            
            synchronized (_lock)
            {
                Thread.sleep(100);
            }
        }
    }
}
