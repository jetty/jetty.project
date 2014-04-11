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

package org.eclipse.jetty.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Atomics
{
    private Atomics()
    {
    }

    public static void updateMin(AtomicLong currentMin, long newValue)
    {
        long oldValue = currentMin.get();
        while (newValue < oldValue)
        {
            if (currentMin.compareAndSet(oldValue, newValue))
                break;
            oldValue = currentMin.get();
        }
    }

    public static void updateMax(AtomicLong currentMax, long newValue)
    {
        long oldValue = currentMax.get();
        while (newValue > oldValue)
        {
            if (currentMax.compareAndSet(oldValue, newValue))
                break;
            oldValue = currentMax.get();
        }
    }

    public static void updateMin(AtomicInteger currentMin, int newValue)
    {
        int oldValue = currentMin.get();
        while (newValue < oldValue)
        {
            if (currentMin.compareAndSet(oldValue, newValue))
                break;
            oldValue = currentMin.get();
        }
    }

    public static void updateMax(AtomicInteger currentMax, int newValue)
    {
        int oldValue = currentMax.get();
        while (newValue > oldValue)
        {
            if (currentMax.compareAndSet(oldValue, newValue))
                break;
            oldValue = currentMax.get();
        }
    }
}
