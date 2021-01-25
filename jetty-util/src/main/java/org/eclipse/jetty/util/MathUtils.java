//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
}
