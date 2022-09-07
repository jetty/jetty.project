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

package org.eclipse.jetty.util.thread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SerializedExecutorTest
{
    QueuedThreadPool _threadPool = new QueuedThreadPool(10, 1);
    SerializedExecutor _serialExec;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _threadPool.start();
        _serialExec = new SerializedExecutor(_threadPool);
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        _threadPool.stop();
    }

    @Test
    public void testSimple() throws Exception
    {
        CountDownLatch running0 = new CountDownLatch(1);
        CountDownLatch waiting0 = new CountDownLatch(1);
        _serialExec.execute(() ->
        {
            try
            {
                running0.countDown();
                waiting0.await();
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        });

        assertTrue(running0.await(10, TimeUnit.SECONDS));
        assertThat(_threadPool.getThreads(), is(1));
        assertThat(_threadPool.getIdleThreads(), is(0));

        CountDownLatch running1 = new CountDownLatch(1);
        CountDownLatch waiting1 = new CountDownLatch(1);
        _serialExec.execute(() ->
        {
            try
            {
                running1.countDown();
                waiting1.await();
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        });

        assertFalse(running1.await(100, TimeUnit.MILLISECONDS));
        assertThat(_threadPool.getThreads(), is(1));
        assertThat(_threadPool.getIdleThreads(), is(0));

        waiting0.countDown();

        assertTrue(running1.await(10, TimeUnit.SECONDS));
        assertThat(_threadPool.getThreads(), is(1));
        assertThat(_threadPool.getIdleThreads(), is(0));

        waiting1.countDown();
        await().atMost(10, TimeUnit.SECONDS).until(() -> _threadPool.getIdleThreads() == 1);

        assertThat(_threadPool.getThreads(), is(1));
        assertThat(_threadPool.getIdleThreads(), is(1));
    }
}
