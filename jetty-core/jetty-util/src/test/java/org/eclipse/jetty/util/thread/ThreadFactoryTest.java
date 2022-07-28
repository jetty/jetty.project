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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.ExceptionUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ThreadFactoryTest
{
    @Test
    public void testThreadFactory() throws Exception
    {
        ThreadGroup threadGroup = new ThreadGroup("my-group");
        MyThreadFactory threadFactory = new MyThreadFactory(threadGroup);

        QueuedThreadPool qtp = new QueuedThreadPool(200, 10, 2000, 0, null, threadGroup, threadFactory);
        try
        {
            qtp.start();

            int testThreads = 2000;
            CountDownLatch threadsLatch = new CountDownLatch(testThreads);
            final ExceptionUtil.MultiException multiException = new ExceptionUtil.MultiException();

            for (int i = 0; i < testThreads; i++)
            {
                qtp.execute(() -> multiException.callAndCatch(() ->
                {
                    TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(20, 500));
                    Thread thread = Thread.currentThread();

                    if (!thread.getName().startsWith("My-"))
                    {
                        multiException.add(new AssertionError("Thread " + thread.getName() + " does not start with 'My-'"));
                    }

                    if (!thread.getThreadGroup().getName().equalsIgnoreCase("my-group"))
                    {
                        multiException.add(new AssertionError("Thread Group " + thread.getThreadGroup().getName() + " is not 'my-group'"));
                    }

                    threadsLatch.countDown();
                }));
            }

            assertTrue(threadsLatch.await(5, TimeUnit.SECONDS), "Did not see all tasks finish");
            multiException.ifExceptionThrow();
        }
        finally
        {
            qtp.stop();
        }
    }

    public static class MyThreadFactory implements ThreadFactory
    {
        private final ThreadGroup threadGroup;

        public MyThreadFactory(ThreadGroup threadGroup)
        {
            this.threadGroup = threadGroup;
        }

        @Override
        public Thread newThread(Runnable runnable)
        {
            Thread thread = new Thread(threadGroup, runnable);
            thread.setDaemon(false);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.setName("My-" + thread.getId());
            return thread;
        }
    }
}
