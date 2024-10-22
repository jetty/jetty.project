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

package org.eclipse.jetty.util.thread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SerializedInvokerTest
{
    private SerializedInvoker _serializedInvoker;
    private ExecutorService _executor;

    @BeforeEach
    public void beforeEach()
    {
        _serializedInvoker = new SerializedInvoker(SerializedInvokerTest.class);
        _executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    public void afterEach()
    {
        _executor.shutdownNow();
    }

    @Test
    public void testSimple() throws Exception
    {
        Task task1 = new Task();
        Task task2 = new Task();
        Task task3 = new Task();

        Runnable todo = _serializedInvoker.offer(task1);
        assertNull(_serializedInvoker.offer(task2));
        assertNull(_serializedInvoker.offer(task3));

        assertFalse(task1.hasRun());
        assertFalse(task2.hasRun());
        assertFalse(task3.hasRun());
        assertFalse(_serializedInvoker.isCurrentThreadInvoking());

        todo.run();

        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());
        assertFalse(_serializedInvoker.isCurrentThreadInvoking());

        Task task4 = new Task();
        todo = _serializedInvoker.offer(task4);
        todo.run();
        assertTrue(task4.hasRun());
        assertFalse(_serializedInvoker.isCurrentThreadInvoking());
    }

    @Test
    public void testMulti()
    {
        Task task1 = new Task();
        Task task2 = new Task();
        Task task3 = new Task();

        Runnable todo = _serializedInvoker.offer(null, task1, null, task2, null, task3, null);

        assertFalse(task1.hasRun());
        assertFalse(task2.hasRun());
        assertFalse(task3.hasRun());
        assertFalse(_serializedInvoker.isCurrentThreadInvoking());

        todo.run();

        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());
        assertFalse(_serializedInvoker.isCurrentThreadInvoking());

        Task task4 = new Task();
        todo = _serializedInvoker.offer(task4);
        todo.run();
        assertTrue(task4.hasRun());
        assertFalse(_serializedInvoker.isCurrentThreadInvoking());
    }

    @Test
    public void testRecursive()
    {
        Task task3 = new Task();
        Task task2 = new Task()
        {
            @Override
            public void run()
            {
                assertNull(_serializedInvoker.offer(task3));
                super.run();
            }
        };
        Task task1 = new Task()
        {
            @Override
            public void run()
            {
                assertNull(_serializedInvoker.offer(task2));
                super.run();
            }
        };

        Runnable todo = _serializedInvoker.offer(task1);

        assertFalse(task1.hasRun());
        assertFalse(task2.hasRun());
        assertFalse(task3.hasRun());
        assertFalse(_serializedInvoker.isCurrentThreadInvoking());

        todo.run();

        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());
        assertFalse(_serializedInvoker.isCurrentThreadInvoking());

        Task task4 = new Task();
        todo = _serializedInvoker.offer(task4);
        todo.run();
        assertTrue(task4.hasRun());
        assertFalse(_serializedInvoker.isCurrentThreadInvoking());
    }

    public class Task implements Runnable
    {
        final CountDownLatch _run = new CountDownLatch(1);

        boolean hasRun()
        {
            return _run.getCount() == 0;
        }

        @Override
        public void run()
        {
            try
            {
                assertTrue(_serializedInvoker.isCurrentThreadInvoking());
                assertFalse(_executor.submit(() -> _serializedInvoker.isCurrentThreadInvoking()).get());

                _run.countDown();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }
}
