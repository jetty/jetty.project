//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

public class MathUtils
{
    private MathUtils()
    {
    }

    /**
     * Returns whether the sum of the arguments overflows an {@code int}.
     *
     * @param a the first value
     * @param b the second value
     * @return whether the sum of the arguments overflows an {@code int}
     */
    public static boolean sumOverflows(int a, int b)
    {
        try
        {
            Math.addExact(a, b);
            return false;
        }
        catch (ArithmeticException x)
        {
            return true;
        }
    }

    /**
     * Returns the sum of its arguments, capping to {@link Long#MAX_VALUE} if they overflow.
     *
     * @param a the first value
     * @param b the second value
     * @return the sum of the values, capped to {@link Long#MAX_VALUE}
     */
    public static long cappedAdd(long a, long b)
    {
        try
        {
            return Math.addExact(a, b);
        }
        catch (ArithmeticException x)
        {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Returns the sum of its arguments, capping to {@code maxValue}.
     *
     * @param a the first value
     * @param b the second value
     * @return the sum of the values, capped to {@code maxValue}
     */
    public static int cappedAdd(int a, int b, int maxValue)
    {
        try
        {
            int sum = Math.addExact(a, b);
            return Math.min(sum, maxValue);
        }
        catch (ArithmeticException x)
        {
            return maxValue;
        }
    }

    /**
     * Get the next highest power of two
     * @param value An integer
     * @return a power of two that is greater than or equal to {@code value}
     */
    public static int ceilToNextPowerOfTwo(int value)
    {
        if (value < 0)
            throw new IllegalArgumentException("value must not be negative");
        int result = 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(value - 1));
        return result > 0 ? result : Integer.MAX_VALUE;
    }

    /**
     * Computes binary logarithm of the given number ceiled to the next power of two.
     * If the given number is 800, it is ceiled to 1024 which is the closest power of 2 greater than or equal to 800,
     * then 1024 is 10_000_000_000 in binary, i.e.: 1 followed by 10 zeros, and it's also 2 to the power of 10.
     * So for the numbers between 513 and 1024 the returned value is 10.
     * @param value An integer
     * @return the binary logarithm of the given number ceiled to the next power of two.
     */
    public static int ceilLog2(int value)
    {
        return Integer.numberOfTrailingZeros(Integer.highestOneBit(ceilToNextPowerOfTwo(value)));
    }
}
