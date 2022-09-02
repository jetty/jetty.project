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

package org.eclipse.jetty.util;

import java.util.concurrent.TimeUnit;

/**
 * <p>Utility class with methods that deal with {@link System#nanoTime()}.</p>
 */
public class NanoTime
{
    /**
     * @return the current nanoTime via {@link System#nanoTime()}
     */
    public static long now()
    {
        return System.nanoTime();
    }

    /**
     * <p>Calculates the nanoseconds elapsed between two nanoTimes.</p>
     *
     * @param beginNanoTime the begin nanoTime
     * @param endNanoTime the end nanoTime
     * @return the nanoseconds elapsed
     */
    public static long elapsed(long beginNanoTime, long endNanoTime)
    {
        return endNanoTime - beginNanoTime;
    }

    /**
     * <p>Calculates the nanoseconds elapsed between a begin nanoTime and the current nanoTime.</p>
     *
     * @param beginNanoTime the begin nanoTime
     * @return the nanoseconds elapsed between the given begin nanoTime and the current nanoTime
     */
    public static long elapsedFrom(long beginNanoTime)
    {
        return elapsed(beginNanoTime, now());
    }

    /**
     * <p>Calculates the nanoseconds elapsed between the current nanoTime and an end nanoTime.</p>
     *
     * @param endNanoTime the end nanoTime
     * @return the nanoseconds elapsed between the current nanoTime and the given end nanoTime
     */
    public static long elapsedTo(long endNanoTime)
    {
        return elapsed(now(), endNanoTime);
    }

    /**
     * <p>Calculates the milliseconds elapsed between two nanoTimes.</p>
     *
     * @param beginNanoTime the begin nanoTime
     * @param endNanoTime the end nanoTime
     * @return the milliseconds elapsed
     */
    public static long millisElapsed(long beginNanoTime, long endNanoTime)
    {
        return TimeUnit.NANOSECONDS.toMillis(elapsed(beginNanoTime, endNanoTime));
    }

    /**
     * <p>Calculates the milliseconds elapsed between a begin nanoTime and the current nanoTime.</p>
     *
     * @param beginNanoTime the begin nanoTime
     * @return the milliseconds elapsed between the given begin nanoTime and the current nanoTime
     */
    public static long millisElapsedFrom(long beginNanoTime)
    {
        return millisElapsed(beginNanoTime, now());
    }

    /**
     * <p>Calculates the milliseconds elapsed between the current nanoTime and an end nanoTime.</p>
     *
     * @param endNanoTime the end nanoTime
     * @return the milliseconds elapsed between the current nanoTime and the given end nanoTime
     */
    public static long millisElapsedTo(long endNanoTime)
    {
        return millisElapsed(now(), endNanoTime);
    }

    /**
     * <p>Calculates the seconds elapsed between two nanoTimes.</p>
     *
     * @param beginNanoTime the begin nanoTime
     * @param endNanoTime the end nanoTime
     * @return the seconds elapsed
     */
    public static long secondsElapsed(long beginNanoTime, long endNanoTime)
    {
        return TimeUnit.NANOSECONDS.toSeconds(elapsed(beginNanoTime, endNanoTime));
    }

    /**
     * <p>Calculates the seconds elapsed between a begin nanoTime and the current nanoTime.</p>
     *
     * @param beginNanoTime the begin nanoTime
     * @return the seconds elapsed between the given begin nanoTime and the current nanoTime
     */
    public static long secondsElapsedFrom(long beginNanoTime)
    {
        return secondsElapsed(beginNanoTime, now());
    }

    /**
     * <p>Calculates the seconds elapsed between the current nanoTime and an end nanoTime.</p>
     *
     * @param endNanoTime the end nanoTime
     * @return the seconds elapsed between the current nanoTime and the given end nanoTime
     */
    public static long secondsElapsedTo(long endNanoTime)
    {
        return secondsElapsed(now(), endNanoTime);
    }

    /**
     * <p>Returns whether the first nanoTime is strictly before the second nanoTime.</p>
     * <p>Reads as: {@code "is nanoTime1 strictly before nanoTime2?"}.</p>
     * <p>Avoids the common mistake of comparing the 2 nanoTimes with the
     * less-than {@code <} operator, which cannot be used when comparing nanoTimes
     * as specified in {@link System#nanoTime()}.</p>
     *
     * @param nanoTime1 the first nanoTime
     * @param nanoTime2 the second nanoTime
     * @return whether the first nanoTime is strictly before the second nanoTime
     * @see #isBeforeOrSame(long, long)
     */
    public static boolean isBefore(long nanoTime1, long nanoTime2)
    {
        return nanoTime1 - nanoTime2 < 0;
    }

    /**
     * <p>Returns whether the first nanoTime is before or the same as the second nanoTime.</p>
     *
     * @param nanoTime1 the first nanoTime
     * @param nanoTime2 the second nanoTime
     * @return whether the first nanoTime is before or the same as the second nanoTime
     * @see #isBefore(long, long)
     */
    public static boolean isBeforeOrSame(long nanoTime1, long nanoTime2)
    {
        return nanoTime1 - nanoTime2 <= 0;
    }

    /**
     * <p>Spin waits for the specified number of nanoseconds.</p>
     *
     * @param nanos the amount of nanoseconds to spin wait
     */
    public static void spinWait(long nanos)
    {
        long start = now();
        while (elapsedFrom(start) < nanos)
        {
            Thread.onSpinWait();
        }
    }

    private NanoTime()
    {
    }
}
