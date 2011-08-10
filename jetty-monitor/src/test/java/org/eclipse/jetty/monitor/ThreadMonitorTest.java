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
        final AtomicInteger countCpuLogs=new AtomicInteger(0);
        final AtomicInteger countStateLogs=new AtomicInteger(0);
        
        ThreadMonitor monitor = new ThreadMonitor(1000,50,1)
        {
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
        monitor.start();
        
        Spinner spinner = new Spinner();
        Thread runner = new Thread(spinner);
        runner.start();
        
        Thread.sleep(DURATION);
                
        spinner.setDone();
        monitor.stop();
        
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
}
