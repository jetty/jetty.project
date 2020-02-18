//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.thread;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.util.thread.strategy.EatWhatYouKill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

public class EatWhatYouKillTest
{
    private EatWhatYouKill ewyk;

    private void startEWYK(ExecutionStrategy.Producer producer) throws Exception
    {
        QueuedThreadPool executor = new QueuedThreadPool();
        ewyk = new EatWhatYouKill(producer, executor);
        ewyk.start();
        ReservedThreadExecutor tryExecutor = executor.getBean(ReservedThreadExecutor.class);
        // Prime the executor so that there is a reserved thread.
        executor.tryExecute(() ->
        {
        });
        while (tryExecutor.getAvailable() == 0)
        {
            Thread.sleep(10);
        }
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (ewyk != null)
            ewyk.stop();
    }

    @Test
    public void testExceptionThrownByTask() throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(EatWhatYouKill.class))
        {
            AtomicReference<Throwable> detector = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(2);
            BlockingQueue<Task> tasks = new LinkedBlockingQueue<>();
            startEWYK(() ->
            {
                boolean proceed = detector.compareAndSet(null, new Throwable());
                if (proceed)
                {
                    try
                    {
                        latch.countDown();
                        return tasks.poll(1, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException x)
                    {
                        x.printStackTrace();
                        return null;
                    }
                    finally
                    {
                        detector.set(null);
                    }
                }
                else
                {
                    return null;
                }
            });

            // Start production in another thread.
            ewyk.dispatch();

            tasks.offer(new Task(() ->
            {
                try
                {
                    // While thread1 runs this task, simulate
                    // that thread2 starts producing.
                    ewyk.dispatch();
                    // Wait for thread2 to block in produce().
                    latch.await();
                    // Throw to verify that exceptions are handled correctly.
                    throw new RuntimeException();
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                }
            }, Invocable.InvocationType.BLOCKING));

            // Wait until EWYK is idle.
            while (!ewyk.isIdle())
            {
                Thread.sleep(10);
            }

            assertNull(detector.get());
        }
    }

    private static class Task implements Runnable, Invocable
    {
        private final Runnable task;
        private final InvocationType invocationType;

        private Task(Runnable task, InvocationType invocationType)
        {
            this.task = task;
            this.invocationType = invocationType;
        }

        @Override
        public void run()
        {
            task.run();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return invocationType;
        }
    }
}
