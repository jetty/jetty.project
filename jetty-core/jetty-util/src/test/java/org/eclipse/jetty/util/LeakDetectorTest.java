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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LeakDetectorTest
{
    private LeakDetector<Object> leakDetector;

    public void prepare(LeakDetector<Object> leakDetector) throws Exception
    {
        this.leakDetector = leakDetector;
        leakDetector.start();
    }

    public void dispose() throws Exception
    {
        leakDetector.stop();
    }

    private void gc()
    {
        for (int i = 0; i < 3; ++i)
        {
            System.gc();
        }
    }

    @Test
    public void testResourceAcquiredAndReleased() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        prepare(new LeakDetector<Object>()
        {
            @Override
            protected void leaked(LeakInfo leakInfo)
            {
                latch.countDown();
            }
        });

        // Block to make sure "resource" goes out of scope
        {
            Object resource = new Object();
            leakDetector.acquired(resource);
            leakDetector.released(resource);
        }

        gc();

        assertFalse(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testResourceAcquiredAndNotReleased() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        prepare(new LeakDetector<Object>()
        {
            @Override
            protected void leaked(LeakInfo leakInfo)
            {
                latch.countDown();
            }
        });

        leakDetector.acquired(new Object());

        gc();

        assertTrue(latch.await(1, TimeUnit.SECONDS));
    }
}
