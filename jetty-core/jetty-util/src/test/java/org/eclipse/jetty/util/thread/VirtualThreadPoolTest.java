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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledForJreRange;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.condition.OS;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledForJreRange(max = JRE.JAVA_20)
public class VirtualThreadPoolTest
{
    @Test
    public void testNamed() throws Exception
    {
        VirtualThreadPool vtp = new VirtualThreadPool();
        vtp.setName("namedV");
        vtp.start();

        CompletableFuture<String> name = new CompletableFuture<>();
        vtp.execute(() -> name.complete(Thread.currentThread().getName()));

        assertThat(name.get(5, TimeUnit.SECONDS), startsWith("namedV"));

        vtp.stop();
    }

    @Test
    public void testJoin() throws Exception
    {
        VirtualThreadPool vtp = new VirtualThreadPool();
        vtp.start();

        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);

        vtp.execute(() ->
        {
            try
            {
                running.countDown();
                vtp.join();
                joined.countDown();
            }
            catch (Throwable t)
            {
                throw new RuntimeException(t);
            }
        });

        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertThat(joined.getCount(), is(1L));
        vtp.stop();
        assertTrue(joined.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testExecute() throws Exception
    {
        VirtualThreadPool vtp = new VirtualThreadPool();
        vtp.start();

        CountDownLatch ran = new CountDownLatch(1);
        vtp.execute(ran::countDown);
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        vtp.stop();
    }

    @Test
    public void testTry() throws Exception
    {
        VirtualThreadPool vtp = new VirtualThreadPool();
        vtp.start();

        CountDownLatch ran = new CountDownLatch(1);
        assertTrue(vtp.tryExecute(ran::countDown));
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        vtp.stop();
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Fails on Windows")
    public void testTrackingDump() throws Exception
    {
        VirtualThreadPool vtp = new VirtualThreadPool();
        vtp.setTracking(true);
        vtp.start();

        assertThat(vtp.getVirtualThreadsExecutor(), instanceOf(TrackingExecutor.class));
        TrackingExecutor trackingExecutor = (TrackingExecutor)vtp.getVirtualThreadsExecutor();
        assertThat(trackingExecutor.size(), is(0));

        CountDownLatch waitersStarted = new CountDownLatch(2);
        CountDownLatch spinnersStarted = new CountDownLatch(2);
        CountDownLatch spinnerSpinning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Waiter waiter1 = new Waiter(waitersStarted, release);
        Waiter waiter2 = new Waiter(waitersStarted, release);
        Spinner spinner = new Spinner(spinnersStarted, spinnerSpinning, release);
        try
        {
            vtp.execute(waiter1);
            vtp.execute(waiter2);
            assertTrue(waitersStarted.await(5, TimeUnit.SECONDS));
            await().atMost(5, TimeUnit.SECONDS).until(
                () -> waiter1.getThread().getState() == Thread.State.TIMED_WAITING &&
                    waiter2.getThread().getState() == Thread.State.TIMED_WAITING);

            vtp.execute(spinner);
            vtp.execute(spinner);
            assertTrue(spinnersStarted.await(5, TimeUnit.SECONDS));
            assertTrue(spinnerSpinning.await(5, TimeUnit.SECONDS));
            assertThat(trackingExecutor.size(), is(4));

            vtp.setDetailedDump(false);
            String dump = vtp.dump();
            assertThat(count(dump, "VirtualThread[#"), is(4));
            assertThat(count(dump, "/runnable"), is(2));
            assertThat(count(dump, "/runnable@"), greaterThanOrEqualTo(1));
            assertThat(count(dump, "waiting"), is(2));
            assertThat(count(dump, "VirtualThreadPoolTest.java"), is(0));

            vtp.setDetailedDump(true);
            dump = vtp.dump();
            assertThat(count(dump, "VirtualThread[#"), is(4));
            assertThat(count(dump, "/runnable"), is(2));
            assertThat(count(dump, "/runnable@"), greaterThanOrEqualTo(1));
            assertThat(count(dump, "waiting"), is(2));
            assertThat(count(dump, "VirtualThreadPoolTest.java"), is(4));
            assertThat(count(dump, "CountDownLatch.await("), is(2));
        }
        finally
        {
            release.countDown();
            vtp.stop();
        }
    }

    @Test
    public void testMaxConcurrentTasks() throws Exception
    {
        VirtualThreadPool vtp = new VirtualThreadPool();
        vtp.setMaxConcurrentTasks(1);
        vtp.start();

        AtomicBoolean run1 = new AtomicBoolean();
        CountDownLatch latch1 = new CountDownLatch(1);
        vtp.execute(() ->
        {
            try
            {
                // Simulate a blocking call.
                run1.set(true);
                latch1.await();
            }
            catch (InterruptedException x)
            {
                throw new RuntimeException(x);
            }
        });

        // Wait for the first task to acquire the only permit.
        await().atMost(1, TimeUnit.SECONDS).until(run1::get);

        // Try to submit another task, it should not
        // be executed, and the caller must not block.
        CountDownLatch latch2 = new CountDownLatch(1);
        vtp.execute(latch2::countDown);
        assertFalse(latch2.await(1, TimeUnit.SECONDS));

        // Unblocking the first task allows the execution of the second task.
        latch1.countDown();

        assertTrue(latch2.await(5, TimeUnit.SECONDS));

        vtp.stop();
    }

    public static int count(String str, String subStr)
    {
        if (StringUtil.isEmpty(str))
            return 0;

        int count = 0;
        int idx = 0;

        while ((idx = str.indexOf(subStr, idx)) != -1)
        {
            count++;
            idx += subStr.length();
        }

        return count;
    }

    private static class Waiter implements Runnable
    {
        private final CountDownLatch _started;
        private final CountDownLatch _release;
        private volatile Thread _thread;

        public Waiter(CountDownLatch started, CountDownLatch release)
        {
            _started = started;
            _release = release;
        }

        public Thread getThread()
        {
            return _thread;
        }

        @Override
        public void run()
        {
            try
            {
                _thread = Thread.currentThread();
                _started.countDown();
                if (!_release.await(30, TimeUnit.SECONDS))
                    throw new IllegalStateException("timed out waiting for release");
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    private static class Spinner implements Runnable
    {
        private final CountDownLatch _started;
        private final CountDownLatch _spinning;
        private final CountDownLatch _release;

        public Spinner(CountDownLatch started, CountDownLatch spinning, CountDownLatch release)
        {
            _started = started;
            _spinning = spinning;
            _release = release;
        }

        @Override
        public void run()
        {
            _started.countDown();
            // Yield until both spinners have started, even with a single carrier.
            while (_started.getCount() > 0 && _release.getCount() > 0)
                Thread.yield();
            _spinning.countDown();
            while (_release.getCount() > 0)
                Thread.onSpinWait();
        }
    }
}
