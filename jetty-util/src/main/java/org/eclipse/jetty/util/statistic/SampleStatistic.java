//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.util.Atomics;


/**
 * SampledStatistics
 * <p>
 * Provides max, total, mean, count, variance, and standard deviation of continuous sequence of samples.
 * <p>
 * Calculates estimates of mean, variance, and standard deviation characteristics of a sample using a non synchronized
 * approximation of the on-line algorithm presented in <cite>Donald Knuth's Art of Computer Programming, Volume 2,
 * Semi numerical Algorithms, 3rd edition, page 232, Boston: Addison-Wesley</cite>. that cites a 1962 paper by B.P. Welford that
 * can be found by following <a href="http://www.jstor.org/pss/1266577">Note on a Method for Calculating Corrected Sums
 * of Squares and Products</a>
 * <p>
 * This algorithm is also described in Wikipedia at <a href=
 * "http://en.wikipedia.org/w/index.php?title=Algorithms_for_calculating_variance&amp;section=4#On-line_algorithm">
 * Algorithms for calculating variance </a>
 */
public class SampleStatistic
{
    protected final LongAccumulator _max = new LongAccumulator(Math::max,0L);
    protected final AtomicLong _total = new AtomicLong();
    protected final AtomicLong _count = new AtomicLong();
    protected final LongAdder _totalVariance100 = new LongAdder();

    public void reset()
    {
        _max.reset();
        _total.set(0);
        _count.set(0);
        _totalVariance100.reset();
    }

    public void set(final long sample)
    {
        long total = _total.addAndGet(sample);
        long count = _count.incrementAndGet();

        if (count>1)
        {
            long mean10 = total*10/count;
            long delta10 = sample*10 - mean10;
            _totalVariance100.add(delta10*delta10);
        }

        _max.accumulate(sample);
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
        final long variance100 = _totalVariance100.sum();
        final long count = _count.get();

        return count>1?((double)variance100)/100.0/(count-1):0.0;
    }

    public double getStdDev()
    {
        return Math.sqrt(getVariance());
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("%s@%x{c=%d,m=%d,t=%d,v100=%d}",this.getClass().getSimpleName(),hashCode(),_count.get(),_max.get(),_total.get(),_totalVariance100.sum());
    }
}
