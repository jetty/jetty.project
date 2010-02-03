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

package org.eclipse.jetty.util;


/* ------------------------------------------------------------ */
/**
 * StatsEstimator
 * 
 * Calculates estimates of mean, variance, and standard deviation
 * characteristics of a sample using on-line algorithm presented
 * in Donald Knuth's Art of Computer Programming, Volume 2,
 * Seminumerical Algorithms, 3rd edition, page 232,
 * Boston: Addison-Wesley. that cites a 1962 paper by B.P. Welford
 * that can be found by following the link below.
 *
 * http://www.jstor.org/pss/1266577
 * 
 * This algorithm can be found in Wikipedia article about computing
 * standard deviation found by following the link below.
 * 
 * http://en.wikipedia.org/w/index.php?title=Algorithms_for_calculating_variance&section=4#On-line_algorithm
 */
public class RunningStats
{
    private volatile long _size;
    private volatile double _mean;
    private volatile double _rsum;
    
    public synchronized void reset()
    {
        _size = 0;
        _mean = 0.0;
        _rsum = 0.0;
    }
    
    public synchronized void update(final double x)
    {
        double mean = _mean;
        _mean += (x - mean) / ++_size;
        _rsum += (x - mean) * (x - _mean);
    }
    
    public long getSize()
    {
        return _size;
    }
    
    public double getMean()
    {
        return _mean;
    }
    
    public synchronized double getVariance()
    {
        return _size > 1 ? _rsum/(_size-1) : 0.0;
    }
    
    public synchronized double getStdDev()
    {
        return Math.sqrt(getVariance());
    }
}
