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

package org.eclipse.jetty.util.statistic;

import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.Atomics;


/* ------------------------------------------------------------ */
/**
 * SampledStatistics
 * <p>
 * Provides max, total, mean, count, variance, and standard
 * deviation of continuous sequence of samples.
 * <p>
 * Calculates estimates of mean, variance, and standard deviation
 * characteristics of a sample using a non synchronized
 * approximation of the on-line algorithm presented
 * in Donald Knuth's Art of Computer Programming, Volume 2,
 * Seminumerical Algorithms, 3rd edition, page 232,
 * Boston: Addison-Wesley. that cites a 1962 paper by B.P. Welford
 * that can be found by following the link http://www.jstor.org/pss/1266577
 * <p>
 * This algorithm is also described in Wikipedia at
 * http://en.wikipedia.org/w/index.php?title=Algorithms_for_calculating_variance&section=4#On-line_algorithm
 */
public class SampleStatistic
{
    protected final AtomicLong _max = new AtomicLong();
    protected final AtomicLong _total = new AtomicLong();
    protected final AtomicLong _count = new AtomicLong();
    protected final AtomicLong _totalVariance100 = new AtomicLong();

    public void reset()
    {
        _max.set(0);
        _total.set(0);
        _count.set(0);
        _totalVariance100.set(0);
    }

    public void set(final long sample)
    {
        long total = _total.addAndGet(sample);
        long count = _count.incrementAndGet();

        if (count>1)
        {
            long mean10 = total*10/count;
            long delta10 = sample*10 - mean10;
            _totalVariance100.addAndGet(delta10*delta10);
        }

        Atomics.updateMax(_max, sample);
    }

    /**
     * @return the max value
     */
    public long getMax()
    {
        return _max.get();
    }

    public long getTotal()
    {
        return _total.get();
    }

    public long getCount()
    {
        return _count.get();
    }

    public double getMean()
    {
        return (double)_total.get()/_count.get();
    }

    public double getVariance()
    {
        final long variance100 = _totalVariance100.get();
        final long count = _count.get();

        return count>1?((double)variance100)/100.0/(count-1):0.0;
    }

    public double getStdDev()
    {
        return Math.sqrt(getVariance());
    }
}
