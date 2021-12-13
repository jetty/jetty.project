//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SerializedExecutorTest
{
    Queue<Runnable> _execQueue = new ConcurrentLinkedQueue<>();
    Executor _executor = command -> _execQueue.add(command);
    SerializedExecutor _serialExec;

    @BeforeEach
    public void beforeEach()
    {
        _execQueue.clear();
        _serialExec = new SerializedExecutor(_executor);
    }

    @AfterEach
    public void afterEach()
    {
        assertThat(_execQueue, empty());
    }

    @Test
    public void testSimple() throws Exception
    {
        Task task1 = new Task();
        Task task2 = new Task();
        Task task3 = new Task();

        Runnable todo = _serialExec.offer(task1);
        assertNull(_serialExec.offer(task2));
        assertNull(_serialExec.offer(task3));

        assertFalse(task1.hasRun());
        assertFalse(task2.hasRun());
        assertFalse(task3.hasRun());

        todo.run();

        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());

        Task task4 = new Task();
        todo = _serialExec.offer(task4);
        todo.run();
        assertTrue(task4.hasRun());
    }

    @Test
    public void testMulti()
    {
        Task task1 = new Task();
        Task task2 = new Task();
        Task task3 = new Task();

        Runnable todo = _serialExec.offer(null, task1, null, task2, null, task3, null);

        assertFalse(task1.hasRun());
        assertFalse(task2.hasRun());
        assertFalse(task3.hasRun());

        todo.run();

        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());

        Task task4 = new Task();
        todo = _serialExec.offer(task4);
        todo.run();
        assertTrue(task4.hasRun());
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
                assertNull(_serialExec.offer(task3));
                super.run();
            }
        };
        Task task1 = new Task()
        {
            @Override
            public void run()
            {
                assertNull(_serialExec.offer(task2));
                super.run();
            }
        };

        Runnable todo = _serialExec.offer(task1);

        assertFalse(task1.hasRun());
        assertFalse(task2.hasRun());
        assertFalse(task3.hasRun());

        todo.run();

        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());

        Task task4 = new Task();
        todo = _serialExec.offer(task4);
        todo.run();
        assertTrue(task4.hasRun());
    }

    @Test
    public void testNonBlocking()
    {
        Task task0 = new Task(Invocable.InvocationType.NON_BLOCKING);
        Task task1 = new Task(Invocable.InvocationType.NON_BLOCKING);
        Task task2 = new Task(Invocable.InvocationType.EITHER)
        {
            @Override
            public void run()
            {
                assertTrue(Invocable.isNonBlockingInvocation());
                super.run();
            }
        };
        Task task3 = new Task(Invocable.InvocationType.BLOCKING);

        Runnable todo = _serialExec.offer(task0, task1, task2, task3);

        assertFalse(task0.hasRun());
        assertFalse(task1.hasRun());
        assertFalse(task2.hasRun());
        assertFalse(task3.hasRun());
        assertThat(Invocable.getInvocationType(todo), equalTo(Invocable.InvocationType.NON_BLOCKING));
        assertThat(_execQueue.size(), is(0));

        todo.run();

        assertTrue(task0.hasRun());
        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertFalse(task3.hasRun());
        assertThat(_execQueue.size(), is(1));

        todo = _execQueue.poll();
        assertThat(todo, notNullValue());
        assertThat(Invocable.getInvocationType(todo), equalTo(Invocable.InvocationType.BLOCKING));

        todo.run();
        assertTrue(task0.hasRun());
        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());
    }

    @Test
    public void testBlocking()
    {
        Task task0 = new Task(Invocable.InvocationType.BLOCKING);
        Runnable todo = _serialExec.offer(task0);
        assertThat(Invocable.getInvocationType(todo), is(Invocable.InvocationType.BLOCKING));

        Task task1 = new Task(Invocable.InvocationType.NON_BLOCKING);
        Task task2 = new Task(Invocable.InvocationType.BLOCKING);
        Task task3 = new Task(Invocable.InvocationType.EITHER)
        {
            @Override
            public void run()
            {
                assertFalse(Invocable.isNonBlockingInvocation());
                super.run();
            }
        };
        assertNull(_serialExec.offer(task1, task2, task3));

        todo.run();
        assertThat(_execQueue.size(), is(0));
        assertTrue(task0.hasRun());
        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());
    }

    @Test
    public void testEitherAsBlocking()
    {
        Task task0 = new Task(Invocable.InvocationType.EITHER);
        Runnable todo = _serialExec.offer(task0);
        assertThat(Invocable.getInvocationType(todo), is(Invocable.InvocationType.EITHER));

        Task task1 = new Task(Invocable.InvocationType.NON_BLOCKING);
        Task task2 = new Task(Invocable.InvocationType.BLOCKING);
        Task task3 = new Task(Invocable.InvocationType.EITHER)
        {
            @Override
            public void run()
            {
                assertFalse(Invocable.isNonBlockingInvocation());
                super.run();
            }
        };
        assertNull(_serialExec.offer(task1, task2, task3));

        todo.run();
        assertThat(_execQueue.size(), is(0));
        assertTrue(task0.hasRun());
        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());
    }

    @Test
    public void testEitherAsNonBlocking()
    {
        Task task0 = new Task(Invocable.InvocationType.EITHER);
        Runnable todo = _serialExec.offer(task0);
        assertThat(Invocable.getInvocationType(todo), is(Invocable.InvocationType.EITHER));

        Task task1 = new Task(Invocable.InvocationType.EITHER)
        {
            @Override
            public void run()
            {
                assertTrue(Invocable.isNonBlockingInvocation());
                super.run();
            }
        };
        Task task2 = new Task(Invocable.InvocationType.NON_BLOCKING);
        Task task3 = new Task(Invocable.InvocationType.BLOCKING);
        assertNull(_serialExec.offer(task1, task2, task3));

        Invocable.invokeNonBlocking(todo);

        assertThat(_execQueue.size(), is(1));
        assertTrue(task0.hasRun());
        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertFalse(task3.hasRun());

        todo = _execQueue.poll();
        assertThat(todo, notNullValue());
        assertThat(Invocable.getInvocationType(todo), is(Invocable.InvocationType.BLOCKING));
        assertThat(Invocable.getInvocationType(todo), equalTo(Invocable.InvocationType.BLOCKING));

        todo.run();
        assertTrue(task0.hasRun());
        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());
    }

    @Test
    public void testSelfInvocation()
    {
        Task task1 = new Task();
        Task task2 = new Task();
        Runnable todo = _serialExec.offer(() -> _serialExec.execute(_serialExec.offer(task1)), task2);

        todo.run();

        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
    }

    public static class Task implements Runnable, Invocable
    {
        final InvocationType _type;
        CountDownLatch _run = new CountDownLatch(1);

        public Task()
        {
            this(InvocationType.BLOCKING);
        }

        public Task(InvocationType type)
        {
            _type = type;
        }

        boolean hasRun()
        {
            return _run.getCount() == 0;
        }

        @Override
        public void run()
        {
            _run.countDown();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _type;
        }
    }
}
