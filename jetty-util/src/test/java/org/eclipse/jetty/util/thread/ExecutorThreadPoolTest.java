//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util.thread;

import java.time.Duration;

import org.eclipse.jetty.util.thread.ThreadPool.SizedThreadPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

public class ExecutorThreadPoolTest extends AbstractThreadPoolTest
{
    @Override
    protected SizedThreadPool newPool(int max)
    {
        return new ExecutorThreadPool(max);
    }

    @Test
    public void testJoin() throws Exception
    {
        final long stopTimeout = 100;
        ExecutorThreadPool executorThreadPool = new ExecutorThreadPool(10);
        executorThreadPool.setStopTimeout(stopTimeout);
        executorThreadPool.start();

        // Verify that join does not timeout after waiting twice the stopTimeout.
        assertThrows(Throwable.class, () ->
            assertTimeoutPreemptively(Duration.ofMillis(stopTimeout * 2), executorThreadPool::join)
        );

        // After stopping the ThreadPool join should unblock.
        executorThreadPool.stop();
        assertTimeoutPreemptively(Duration.ofMillis(stopTimeout), executorThreadPool::join);
    }
}
