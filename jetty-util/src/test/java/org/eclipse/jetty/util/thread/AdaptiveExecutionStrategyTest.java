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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

public class AdaptiveExecutionStrategyTest
{
    private AdaptiveExecutionStrategy aes;

    private void startAES(ExecutionStrategy.Producer producer) throws Exception
    {
        QueuedThreadPool executor = new QueuedThreadPool();
        aes = new AdaptiveExecutionStrategy(producer, executor);
        aes.start();
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
        if (aes != null)
            aes.stop();
    }

    @Test
    public void testExceptionThrownByTask() throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(AdaptiveExecutionStrategy.class))
        {
            AtomicReference<Throwable> detector = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(2);
            BlockingQueue<Invocable.ReadyTask> tasks = new LinkedBlockingQueue<>();
            startAES(() ->
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
            aes.dispatch();

            tasks.offer(new Invocable.ReadyTask(Invocable.InvocationType.BLOCKING, () ->
            {
                try
                {
                    // While thread1 runs this task, simulate
                    // that thread2 starts producing.
                    aes.dispatch();
                    // Wait for thread2 to block in produce().
                    latch.await();
                    // Throw to verify that exceptions are handled correctly.
                    throw new RuntimeException();
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                }
            }));

            // Wait until AES is idle.
            while (!aes.isIdle())
            {
                Thread.sleep(10);
            }

            assertNull(detector.get());
        }
    }
}
