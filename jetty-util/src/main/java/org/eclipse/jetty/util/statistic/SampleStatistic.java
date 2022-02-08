//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.statistic;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * <p>Statistics on a sampled value.</p>
 * <p>Provides max, total, mean, count, variance, and standard deviation of continuous sequence of samples.</p>
 * <p>Calculates estimates of mean, variance, and standard deviation characteristics of a sample using a non synchronized
 * approximation of the on-line algorithm presented in <cite>Donald Knuth's Art of Computer Programming, Volume 2,
 * Semi numerical Algorithms, 3rd edition, page 232, Boston: Addison-Wesley</cite>. That cites a 1962 paper by B.P. Welford:
 * <a href="http://www.jstor.org/pss/1266577">Note on a Method for Calculating Corrected Sums of Squares and Products</a></p>
 * <p>This algorithm is also described in Wikipedia in the section "Online algorithm":
 * <a href="https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance">Algorithms for calculating variance</a>.</p>
 */
public class SampleStatistic
{
    private final LongAccumulator _max = new LongAccumulator(Math::max, 0L);
    private final AtomicLong _total = new AtomicLong();
    private final AtomicLong _count = new AtomicLong();
    private final LongAdder _totalVariance100 = new LongAdder();

    /**
     * Resets the statistics.
     */
    public void reset()
    {
        _max.reset();
        _total.set(0);
        _count.set(0);
        _totalVariance100.reset();
    }

    /**
     * Records a sample value.
     *
     * @param sample the value to record.
     */
    public void record(long sample)
    {
        long total = _total.addAndGet(sample);
        long count = _count.incrementAndGet();

        if (count > 1)
        {
            long mean10 = total * 10 / count;
            long delta10 = sample * 10 - mean10;
            _totalVariance100.add(delta10 * delta10);
        }

        _max.accumulate(sample);
    }

    /**
     * @return the max value of the recorded samples
     */
    public long getMax()
    {
        return _max.get();
    }

    /**
     * @return the sum of all the recorded samples
     */
    public long getTotal()
    {
        return _total.get();
    }

    /**
     * @return the number of samples recorded
     */
    public long getCount()
    {
        return _count.get();
    }

    /**
     * @return the average value of the samples recorded, or zero if there are no samples
     */
    public double getMean()
    {
        long count = getCount();
        return count > 0 ? (double)_total.get() / _count.get() : 0.0D;
    }

    /**
     * @return the variance of the samples recorded, or zero if there are less than 2 samples
     */
    public double getVariance()
    {
        long variance100 = _totalVariance100.sum();
        long count = getCount();
        return count > 1 ? variance100 / 100.0D / (count - 1) : 0.0D;
    }

    /**
     * @return the standard deviation of the samples recorded
     */
    public double getStdDev()
    {
        return Math.sqrt(getVariance());
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{count=%d,max=%d,mean=%f,total=%d,stddev=%f}", getClass().getSimpleName(), hashCode(), getCount(), getMax(), getMean(), getTotal(), getStdDev());
    }
}
