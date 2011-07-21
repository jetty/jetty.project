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

import java.lang.management.ThreadInfo;
import java.util.List;

import org.junit.Test;


/* ------------------------------------------------------------ */
/**
 */
public class ThreadMonitorTest
{
    private int count;
    
    @Test
    public void monitorTest() throws Exception
    {
        count = 0;
        
        ThreadMonitor monitor = new ThreadMonitor(1,95,2)
        {
            @Override
            protected void dump(List<ThreadInfo> threads)
            {
                ++count;
                super.dump(threads);
            }
        };
        monitor.start();
        
        Spinner spinner = new Spinner();
        Thread runner = new Thread(spinner);
        runner.start();
        
        Thread.sleep(15000);
        
        spinner.setDone();
        monitor.stop();
        
        assertTrue(count > 10);
    }


    private class Spinner implements Runnable
    {
        private boolean done = false;

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
            while (!done)
            {
                foo();
            }
        }
        
        private void foo()
        {
            for (int i=0; i<Integer.MAX_VALUE; i++)
            {
                long f = 1;
                for(int j=1; j<=i; j++)
                {
                    f += j;
                }
            }
        }
    }
}
