// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.server;

import org.eclipse.jetty.util.RunningStats;
import org.eclipse.jetty.util.SimpleStats;


/* ------------------------------------------------------------ */
/**
 * ServerStats
 * 
 * Aggregates classes that computes statistic values 
 */
public class ServerStats
{
    private ServerStats() {}
    
    /* ------------------------------------------------------------ */
    /**
     * CounterStats
     * 
     * Computes statistic values for counter variables
     */
    public static class CounterStats
        extends SimpleStats
    {
        /* ------------------------------------------------------------ */
        /**
         * Construct the request statistics object
         */
        public CounterStats()
        {
            super(true, true, false, true); // track current, total, and max
        }
        
        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.util.SimpleStats#set(long)
         */
        @Override
        public void set(long value)
        {
            throw new UnsupportedOperationException();
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * MeasuredStats
     * 
     * Computes statistic values for measured variables
     */
    public static class MeasuredStats
        extends SimpleStats
    {
        private final RunningStats _stats = new RunningStats();
        
        /* ------------------------------------------------------------ */
        /**
         * Construct the request statistics object
         */
        public MeasuredStats()
        {
            super(false, true, false, true); // track total and max only
        }
        

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.util.SimpleStats#reset()
         */
        @Override
        public void reset()
        {
            super.reset();
            _stats.reset();
        }
        
        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.util.SimpleStats#set(long)
         */
        @Override
        public void set(long value)
        {
            super.set(value);
            _stats.update(value);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.util.SimpleStats#add(long)
         */
        @Override
        public void add(long delta)
        {
            throw new UnsupportedOperationException();
        }

        /* ------------------------------------------------------------ */
        /**
         * @return mean value of requests per connection
         */
        public double getMean()
        {
            return _stats.getMean();
        }
        
        /* ------------------------------------------------------------ */
        /**
         * @return standard deviation of requests per connection
         */
        public double getStdDev()
        {
            return _stats.getStdDev();
        }
    }
}
