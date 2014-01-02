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

package org.eclipse.jetty.monitor;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.Test;


/* ------------------------------------------------------------ */
/**
 */
public class ThreadMonitorTest
{
    public final static int DURATION=4000;
    
    @Test
    public void monitorTest() throws Exception
    {
        ((StdErrLog)Log.getLogger(ThreadMonitor.class.getName())).setHideStacks(true);
        ((StdErrLog)Log.getLogger(ThreadMonitor.class.getName())).setSource(false);
        
        final AtomicInteger countLogs=new AtomicInteger(0);
        final AtomicInteger countSpin=new AtomicInteger(0);
        
        ThreadMonitor monitor = new ThreadMonitor(1000,50,1,1)
        {
            @Override
            protected void logThreadInfo(boolean logAll)
            {
                if (logAll)
                    countLogs.incrementAndGet();
                else
                    countSpin.incrementAndGet();
                super.logThreadInfo(logAll);
            }
        };
        monitor.setDumpable(new Dumpable()
        {
            public void dump(Appendable out, String indent) throws IOException
            {
                out.append(dump());
            }
            
            public String dump()
            {
                return "Dump Spinning";
            }
        });
        
        monitor.logCpuUsage(2000,0);
        monitor.start();
        
        Random rnd = new Random();
        for (long cnt=0; cnt<100; cnt++)
        {
            long value = rnd.nextLong() % 50 + 50;
            Sleeper sleeper = new Sleeper(value);
            Thread runner = new Thread(sleeper);
            runner.setDaemon(true);
            runner.start();
        }
        
        Spinner spinner = new Spinner();
        Thread runner = new Thread(spinner);
        runner.start();
        
        Thread.sleep(DURATION);
                
        spinner.setDone();
        monitor.stop();
        
        assertTrue(countLogs.get() >= 1);
        assertTrue(countSpin.get() >= 2);
    }


    private class Spinner implements Runnable
    {
        private volatile boolean done = false;

        /* ------------------------------------------------------------ */
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
    
    private class Sleeper implements Runnable
    {
        private long _value;
        
        /* ------------------------------------------------------------ */
        public Sleeper(long value)
        {
            _value = value;
        }
        
        /* ------------------------------------------------------------ */
        public void run()
        {
            try
            {
                fn(_value);
            }
            catch (InterruptedException e) {}
        }
        
        /* ------------------------------------------------------------ */
        public long fn(long value) throws InterruptedException
        {
            long result = value > 1 ? fn(value-1) : 1;
            
            Thread.sleep(50);
            
            return result;
        }
    }
}
