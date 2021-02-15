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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Atomics
{
    private Atomics()
    {
    }

    public static boolean updateMin(AtomicLong currentMin, long newValue)
    {
        long oldValue = currentMin.get();
        while (newValue < oldValue)
        {
            if (currentMin.compareAndSet(oldValue, newValue))
                return true;
            oldValue = currentMin.get();
        }
        return false;
    }

    public static boolean updateMax(AtomicLong currentMax, long newValue)
    {
        long oldValue = currentMax.get();
        while (newValue > oldValue)
        {
            if (currentMax.compareAndSet(oldValue, newValue))
                return true;
            oldValue = currentMax.get();
        }
        return false;
    }

    public static boolean updateMin(AtomicInteger currentMin, int newValue)
    {
        int oldValue = currentMin.get();
        while (newValue < oldValue)
        {
            if (currentMin.compareAndSet(oldValue, newValue))
                return true;
            oldValue = currentMin.get();
        }
        return false;
    }

    public static boolean updateMax(AtomicInteger currentMax, int newValue)
    {
        int oldValue = currentMax.get();
        while (newValue > oldValue)
        {
            if (currentMax.compareAndSet(oldValue, newValue))
                return true;
            oldValue = currentMax.get();
        }
        return false;
    }
}
