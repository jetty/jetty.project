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

package org.eclipse.jetty.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

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
            System.gc();
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

        Assert.assertFalse(latch.await(1, TimeUnit.SECONDS));
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

        Assert.assertTrue(latch.await(1, TimeUnit.SECONDS));
    }
}
