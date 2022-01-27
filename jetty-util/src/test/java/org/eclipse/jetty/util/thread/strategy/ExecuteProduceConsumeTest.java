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

package org.eclipse.jetty.util.thread.strategy;

import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.util.thread.ExecutionStrategy.Producer;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExecuteProduceConsumeTest
{
    private static final Runnable NULLTASK = () ->
    {
    };

    private final BlockingQueue<Runnable> _produce = new LinkedBlockingQueue<>();
    private final Queue<Runnable> _executions = new LinkedBlockingQueue<>();
    private ExecuteProduceConsume _exStrategy;
    private volatile Thread _producer;

    @BeforeEach
    public void before()
    {
        _executions.clear();

        Producer producer = () ->
        {
            try
            {
                _producer = Thread.currentThread();
                Runnable task = _produce.take();
                if (task == NULLTASK)
                    return null;
                return task;
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
                return null;
            }
            finally
            {
                _producer = null;
            }
        };

        Executor executor = _executions::add;

        _exStrategy = new ExecuteProduceConsume(producer, executor);
    }

    @AfterEach
    public void after()
    {
        // All done and checked
        assertThat(_produce.size(), Matchers.equalTo(0));
        assertThat(_executions.size(), Matchers.equalTo(0));
    }

    @Test
    public void testIdle()
    {
        _produce.add(NULLTASK);
        _exStrategy.produce();
    }

    @Test
    public void testProduceOneNonBlockingTask()
    {
        Task t0 = new Task();
        _produce.add(t0);
        _produce.add(NULLTASK);
        _exStrategy.produce();
        assertThat(t0.hasRun(), Matchers.equalTo(true));
        assertEquals(_exStrategy, _executions.poll());
    }

    @Test
    public void testProduceManyNonBlockingTask()
    {
        Task[] tasks = new Task[10];
        for (int i = 0; i < tasks.length; i++)
        {
            tasks[i] = new Task();
            _produce.add(tasks[i]);
        }
        _produce.add(NULLTASK);
        _exStrategy.produce();

        for (Task task : tasks)
        {
            assertThat(task.hasRun(), Matchers.equalTo(true));
        }
        assertEquals(_exStrategy, _executions.poll());
    }

    @Test
    public void testProduceOneBlockingTaskIdleByDispatch() throws Exception
    {
        final Task t0 = new Task(true);
        Thread thread = new Thread()
        {
            @Override
            public void run()
            {
                _produce.add(t0);
                _produce.add(NULLTASK);
                _exStrategy.produce();
            }
        };
        thread.start();

        // wait for execute thread to block in
        t0.awaitRun();
        assertEquals(thread, t0.getThread());

        // Should have dispatched only one helper
        assertEquals(_exStrategy, _executions.poll());
        // which is make us idle
        _exStrategy.run();
        assertThat(_exStrategy.isIdle(), Matchers.equalTo(true));

        // unblock task
        t0.unblock();
        // will run to completion because are already idle
        thread.join();
    }

    @Test
    public void testProduceOneBlockingTaskIdleByTask() throws Exception
    {
        final Task t0 = new Task(true);
        Thread thread = new Thread()
        {
            @Override
            public void run()
            {
                _produce.add(t0);
                _produce.add(NULLTASK);
                _exStrategy.produce();
            }
        };
        thread.start();

        // wait for execute thread to block in
        t0.awaitRun();

        // Should have dispatched only one helper
        assertEquals(_exStrategy, _executions.poll());

        // unblock task
        t0.unblock();
        // will run to completion because are become idle
        thread.join();
        assertThat(_exStrategy.isIdle(), Matchers.equalTo(true));

        // because we are idle, dispatched thread is noop
        _exStrategy.run();
    }

    @Test
    public void testBlockedInProduce() throws Exception
    {
        final Task t0 = new Task(true);
        Thread thread0 = new Thread()
        {
            @Override
            public void run()
            {
                _produce.add(t0);
                _exStrategy.produce();
            }
        };
        thread0.start();

        // wait for execute thread to block in task
        t0.awaitRun();
        assertEquals(thread0, t0.getThread());

        // Should have dispatched another helper
        assertEquals(_exStrategy, _executions.poll());

        // dispatched thread will block in produce
        Thread thread1 = new Thread(_exStrategy);
        thread1.start();

        // Spin
        while (_producer == null)
        {
            Thread.yield();
        }

        // thread1 is blocked in producing
        assertEquals(thread1, _producer);

        // because we are producing, any other dispatched threads are noops
        _exStrategy.run();

        // ditto with execute
        _exStrategy.produce();

        // Now if unblock the production by the dispatched thread
        final Task t1 = new Task(true);
        _produce.add(t1);

        // task will be run by thread1
        t1.awaitRun();
        assertEquals(thread1, t1.getThread());

        // and another thread will have been requested
        assertEquals(_exStrategy, _executions.poll());

        // If we unblock t1, it will overtake t0 and try to produce again!
        t1.unblock();

        // Now thread1 is producing again
        while (_producer == null)
        {
            Thread.yield();
        }
        assertEquals(thread1, _producer);

        // If we unblock t0, it will decide it is not needed
        t0.unblock();
        thread0.join();

        // If the requested extra thread turns up, it is also noop because we are producing
        _exStrategy.run();

        // Give the idle job
        _produce.add(NULLTASK);

        // Which will eventually idle the producer
        thread1.join();
        assertEquals(null, _producer);
    }

    @Test
    public void testExecuteWhileIdling() throws Exception
    {
        final Task t0 = new Task(true);
        Thread thread0 = new Thread()
        {
            @Override
            public void run()
            {
                _produce.add(t0);
                _exStrategy.produce();
            }
        };
        thread0.start();

        // wait for execute thread to block in task
        t0.awaitRun();
        assertEquals(thread0, t0.getThread());

        // Should have dispatched another helper
        assertEquals(_exStrategy, _executions.poll());

        // We will go idle when we next produce
        _produce.add(NULLTASK);

        // execute will return immediately because it did not yet see the idle.
        _exStrategy.produce();

        // When we unblock t0, thread1 will see the idle,
        t0.unblock();

        // and will see new tasks
        final Task t1 = new Task(true);
        _produce.add(t1);
        t1.awaitRun();
        assertThat(t1.getThread(), Matchers.equalTo(thread0));

        // Should NOT have dispatched another helper, because the last is still pending
        assertThat(_executions.size(), Matchers.equalTo(0));

        // When the dispatched thread turns up, it will see the second idle
        _produce.add(NULLTASK);
        _exStrategy.run();
        assertThat(_exStrategy.isIdle(), Matchers.equalTo(true));

        // So that when t1 completes it does not produce again.
        t1.unblock();
        thread0.join();
    }

    private static class Task implements Runnable
    {
        private final CountDownLatch _block = new CountDownLatch(1);
        private final CountDownLatch _run = new CountDownLatch(1);
        private volatile Thread _thread;

        public Task()
        {
            this(false);
        }

        public Task(boolean block)
        {
            if (!block)
                _block.countDown();
        }

        @Override
        public void run()
        {
            try
            {
                _thread = Thread.currentThread();
                _run.countDown();
                _block.await();
            }
            catch (InterruptedException e)
            {
                throw new IllegalStateException(e);
            }
            finally
            {
                _thread = null;
            }
        }

        public boolean hasRun()
        {
            return _run.getCount() <= 0;
        }

        public void awaitRun()
        {
            try
            {
                _run.await();
            }
            catch (InterruptedException e)
            {
                throw new IllegalStateException(e);
            }
        }

        public void unblock()
        {
            _block.countDown();
        }

        public Thread getThread()
        {
            return _thread;
        }
    }
}
