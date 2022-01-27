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
