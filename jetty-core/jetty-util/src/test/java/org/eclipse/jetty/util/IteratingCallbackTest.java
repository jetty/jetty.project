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

package org.eclipse.jetty.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IteratingCallbackTest
{
    private Scheduler scheduler;

    @BeforeEach
    public void prepare() throws Exception
    {
        scheduler = new ScheduledExecutorScheduler();
        scheduler.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        scheduler.stop();
    }

    @Test
    public void testNonWaitingProcess() throws Exception
    {
        TestCB cb = new TestCB()
        {
            int i = 10;

            @Override
            protected Action process() throws Exception
            {
                processed++;
                if (i-- > 1)
                {
                    succeeded(); // fake a completed IO operation
                    return Action.SCHEDULED;
                }
                return Action.SUCCEEDED;
            }
        };

        cb.iterate();
        assertTrue(cb.waitForComplete());
        assertEquals(10, cb.processed);
    }

    @Test
    public void testWaitingProcess() throws Exception
    {
        TestCB cb = new TestCB()
        {
            int i = 4;

            @Override
            protected Action process() throws Exception
            {
                processed++;
                if (i-- > 1)
                {
                    scheduler.schedule(successTask, 50, TimeUnit.MILLISECONDS);
                    return Action.SCHEDULED;
                }
                return Action.SUCCEEDED;
            }
        };

        cb.iterate();

        assertTrue(cb.waitForComplete());

        assertEquals(4, cb.processed);
    }

    @Test
    public void testWaitingProcessSpuriousIterate() throws Exception
    {
        final TestCB cb = new TestCB()
        {
            int i = 4;

            @Override
            protected Action process() throws Exception
            {
                processed++;
                if (i-- > 1)
                {
                    scheduler.schedule(successTask, 50, TimeUnit.MILLISECONDS);
                    return Action.SCHEDULED;
                }
                return Action.SUCCEEDED;
            }
        };

        cb.iterate();
        scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                cb.iterate();
                if (!cb.isSucceeded())
                    scheduler.schedule(this, 50, TimeUnit.MILLISECONDS);
            }
        }, 49, TimeUnit.MILLISECONDS);

        assertTrue(cb.waitForComplete());

        assertEquals(4, cb.processed);
    }

    @Test
    public void testNonWaitingProcessFailure() throws Exception
    {
        TestCB cb = new TestCB()
        {
            int i = 10;

            @Override
            protected Action process() throws Exception
            {
                processed++;
                if (i-- > 1)
                {
                    if (i > 5)
                        succeeded(); // fake a completed IO operation
                    else
                        failed(new Exception("testing"));
                    return Action.SCHEDULED;
                }
                return Action.SUCCEEDED;
            }
        };

        cb.iterate();
        assertFalse(cb.waitForComplete());
        assertEquals(5, cb.processed);
    }

    @Test
    public void testWaitingProcessFailure() throws Exception
    {
        TestCB cb = new TestCB()
        {
            int i = 4;

            @Override
            protected Action process() throws Exception
            {
                processed++;
                if (i-- > 1)
                {
                    scheduler.schedule(i > 2 ? successTask : failTask, 50, TimeUnit.MILLISECONDS);
                    return Action.SCHEDULED;
                }
                return Action.SUCCEEDED;
            }
        };

        cb.iterate();

        assertFalse(cb.waitForComplete());
        assertEquals(2, cb.processed);
    }

    @Test
    public void testIdleWaiting() throws Exception
    {
        final CountDownLatch idle = new CountDownLatch(1);

        TestCB cb = new TestCB()
        {
            int i = 5;

            @Override
            protected Action process()
            {
                processed++;

                return switch (i--)
                {
                    case 5, 2 ->
                    {
                        succeeded();
                        yield Action.SCHEDULED;
                    }
                    case 4, 1 ->
                    {
                        scheduler.schedule(successTask, 5, TimeUnit.MILLISECONDS);
                        yield Action.SCHEDULED;
                    }
                    case 3 ->
                    {
                        scheduler.schedule(idle::countDown, 5, TimeUnit.MILLISECONDS);
                        yield Action.IDLE;
                    }
                    case 0 -> Action.SUCCEEDED;
                    default -> throw new IllegalStateException();
                };
            }
        };

        cb.iterate();
        assertTrue(idle.await(10, TimeUnit.SECONDS));
        assertTrue(cb.isIdle());

        cb.iterate();
        assertTrue(cb.waitForComplete());
        assertEquals(6, cb.processed);
    }

    @Test
    public void testCloseDuringProcessingReturningScheduled() throws Exception
    {
        final CountDownLatch abortLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        IteratingCallback callback = new IteratingCallback()
        {
            @Override
            protected Action process()
            {
                close();
                return Action.SCHEDULED;
            }

            @Override
            protected void onAbort(Throwable cause)
            {
                abortLatch.countDown();
            }

            @Override
            protected void onCompleteFailure(Throwable cause)
            {
                failureLatch.countDown();
            }
        };

        callback.iterate();

        assertFalse(failureLatch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(abortLatch.await(1000000000, TimeUnit.SECONDS));
        assertTrue(callback.isClosed());

        callback.succeeded();
        assertTrue(failureLatch.await(1, TimeUnit.SECONDS));
        assertTrue(callback.isFailed());
        assertTrue(callback.isClosed());
    }

    @Test
    public void testCloseDuringProcessingReturningSucceeded() throws Exception
    {
        final CountDownLatch failureLatch = new CountDownLatch(1);
        IteratingCallback callback = new IteratingCallback()
        {
            @Override
            protected Action process()
            {
                close();
                return Action.SUCCEEDED;
            }

            @Override
            protected void onCompleteFailure(Throwable cause)
            {
                failureLatch.countDown();
            }
        };

        callback.iterate();

        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    }

    private abstract static class TestCB extends IteratingCallback
    {
        protected Runnable successTask = this::succeeded;
        protected Runnable failTask = () -> failed(new Exception("testing failure"));
        protected CountDownLatch completed = new CountDownLatch(1);
        protected int processed = 0;

        @Override
        protected void onCompleteSuccess()
        {
            completed.countDown();
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            completed.countDown();
        }

        boolean waitForComplete() throws InterruptedException
        {
            return completed.await(10, TimeUnit.SECONDS) && isSucceeded();
        }
    }

    @Test
    public void testMultipleFailures() throws Exception
    {
        AtomicInteger process = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        IteratingCallback icb = new IteratingCallback()
        {
            @Override
            protected Action process() throws Throwable
            {
                process.incrementAndGet();
                return Action.SCHEDULED;
            }

            @Override
            protected void onCompleteFailure(Throwable cause)
            {
                super.onCompleteFailure(cause);
                failure.incrementAndGet();
            }
        };

        icb.iterate();
        assertEquals(1, process.get());
        assertEquals(0, failure.get());

        icb.failed(new Throwable("test1"));

        assertEquals(1, process.get());
        assertEquals(1, failure.get());

        icb.succeeded();
        assertEquals(1, process.get());
        assertEquals(1, failure.get());

        icb.failed(new Throwable("test2"));
        assertEquals(1, process.get());
        assertEquals(1, failure.get());
    }

    @Test
    public void testWhenIdleAbortSerializesOnCompleteFailure() throws Exception
    {
        AtomicInteger count = new AtomicInteger();
        CountDownLatch ocfLatch = new CountDownLatch(1);
        IteratingCallback icb = new IteratingCallback()
        {
            @Override
            protected Action process()
            {
                count.incrementAndGet();
                return Action.IDLE;
            }

            @Override
            protected void onCompleteFailure(Throwable cause)
            {
                ocfLatch.countDown();
            }
        };

        icb.iterate();

        assertEquals(1, count.get());

        icb.abort(new Exception());

        assertTrue(ocfLatch.await(5, TimeUnit.SECONDS));
        assertTrue(icb.isFailed());
        assertTrue(icb.isAborted());
        assertEquals(1, count.get());
    }

    @Test
    public void testWhenProcessingAbortSerializesOnCompleteFailure() throws Exception
    {
        AtomicInteger count = new AtomicInteger();
        CountDownLatch ocfLatch = new CountDownLatch(1);
        IteratingCallback icb = new IteratingCallback()
        {
            @Override
            protected Action process() throws Throwable
            {
                count.incrementAndGet();
                abort(new Exception());

                // After calling abort, onCompleteFailure() must not be called yet.
                assertFalse(ocfLatch.await(100, TimeUnit.MILLISECONDS));

                return Action.SCHEDULED;
            }

            @Override
            protected void onCompleteFailure(Throwable cause)
            {
                ocfLatch.countDown();
            }
        };

        icb.iterate();

        assertEquals(1, count.get());

        assertFalse(ocfLatch.await(10, TimeUnit.MILLISECONDS));
        assertTrue(icb.isAborted());

        // Calling succeeded() won't cause further iterations.
        icb.succeeded();

        assertTrue(ocfLatch.await(5, TimeUnit.SECONDS));
        assertTrue(icb.isFailed());
        assertTrue(icb.isAborted());
        assertEquals(1, count.get());
    }

    @Test
    public void testICBSuccess() throws Exception
    {
        TestIteratingCB callback = new TestIteratingCB();
        callback.iterate();
        callback.succeeded();
        assertTrue(callback._completed.await(1, TimeUnit.SECONDS));
        assertThat(callback._completion.getReference(), Matchers.nullValue());
        assertTrue(callback._completion.isMarked());

        // Everything now a noop
        assertFalse(callback.abort(new Throwable()));
        callback.failed(new Throwable());
        assertThat(callback._completion.getReference(), Matchers.nullValue());
        assertThat(callback._completed.getCount(), is(0L));
    }

    @Test
    public void testICBFailure() throws Exception
    {
        Throwable failure = new Throwable();
        TestIteratingCB callback = new TestIteratingCB();
        callback.iterate();
        callback.failed(failure);
        assertTrue(callback._completed.await(1, TimeUnit.SECONDS));
        assertThat(callback._completion.getReference(), Matchers.sameInstance(failure));
        assertTrue(callback._completion.isMarked());

        // Everything now a noop, other than suppression
        callback.succeeded();
        Throwable late = new Throwable();
        assertFalse(callback.abort(late));
        assertFalse(ExceptionUtil.areNotAssociated(failure, late));
        assertThat(callback._completion.getReference(), Matchers.sameInstance(failure));
        assertThat(callback._completed.getCount(), is(0L));
    }

    @Test
    public void testICBAbortSuccess() throws Exception
    {
        TestIteratingCB callback = new TestIteratingCB();
        callback.iterate();

        Throwable abort = new Throwable();
        callback.abort(abort);
        assertFalse(callback._completed.await(100, TimeUnit.MILLISECONDS));
        assertThat(callback._completion.getReference(), Matchers.sameInstance(abort));
        assertFalse(callback._completion.isMarked());

        callback.succeeded();
        assertThat(callback._completion.getReference(), Matchers.sameInstance(abort));
        assertThat(callback._completed.getCount(), is(0L));

        Throwable late = new Throwable();
        callback.failed(late);
        assertFalse(callback.abort(late));
        assertFalse(ExceptionUtil.areNotAssociated(abort, late));
        assertThat(callback._completion.getReference(), Matchers.sameInstance(abort));
        assertThat(callback._completed.getCount(), is(0L));
    }

    public static Stream<Arguments> abortTests()
    {
        List<Arguments> tests = new ArrayList<>();

        for (IteratingCallback.State state : IteratingCallback.State.values())
        {
            String name = state.name();

            if (name.contains("PROCESSING"))
            {
                for (IteratingCallback.Action action : IteratingCallback.Action.values())
                {
                    if (name.contains("CALLED") || action == IteratingCallback.Action.SCHEDULED)
                    {
                        tests.add(Arguments.of(name, action.toString(), Boolean.TRUE));
                        tests.add(Arguments.of(name, action.toString(), Boolean.FALSE));
                    }
                    else
                    {
                        tests.add(Arguments.of(name, action.toString(), null));
                    }
                }
            }
            else if (name.equals("COMPLETE") || name.contains("PENDING"))
            {
                tests.add(Arguments.of(name, null, Boolean.TRUE));
                tests.add(Arguments.of(name, null, Boolean.FALSE));
            }
            else
            {
                tests.add(Arguments.of(name, null, null));
            }
        }

        return tests.stream();
    }

    @ParameterizedTest
    @MethodSource("abortTests")
    public void testAbortInEveryState(String state, String action, Boolean success) throws Exception
    {
        CountDownLatch processLatch = new CountDownLatch(1);

        AtomicReference<Throwable> onAbort = new AtomicReference<>();
        AtomicReference<Throwable> onCompleteFailure = new AtomicReference<>();
        AtomicBoolean onCompleteSuccess = new AtomicBoolean();
        AtomicBoolean onCompleted = new AtomicBoolean();

        IteratingCallback callback = new IteratingCallback()
        {
            @Override
            protected Action process() throws Throwable
            {
                if (state.contains("CALLED"))
                {
                    if (success)
                        succeeded();
                    else
                        failed(new Throwable("failure"));
                }

                if (state.contains("ABORT"))
                    abort(new Throwable("abort in process"));

                if (state.contains("PENDING"))
                    return Action.SCHEDULED;

                if (state.equals("COMPLETE"))
                {
                    if (success)
                        return Action.SUCCEEDED;
                    failed(new Throwable("Complete Failure"));
                    return Action.SCHEDULED;
                }

                if (state.equals("CLOSED"))
                {
                    close();
                    return Action.SUCCEEDED;
                }

                processLatch.await();
                return IteratingCallback.Action.valueOf(action);
            }

            @Override
            protected void onAbort(Throwable cause)
            {
                onAbort.set(cause);
            }

            @Override
            protected void onCompleteSuccess()
            {
                onCompleteSuccess.set(true);
            }

            @Override
            protected void onCompleteFailure(Throwable cause)
            {
                onCompleteFailure.set(cause);
            }

            @Override
            protected void onCompleted(Throwable causeOrNull)
            {
                onCompleted.set(true);
            }
        };

        if (!state.equals("IDLE"))
        {
            new Thread(callback::iterate).start();
        }

        Awaitility.waitAtMost(5, TimeUnit.SECONDS).pollInterval(10, TimeUnit.MILLISECONDS).until(() -> callback.toString().contains(state));
        assertThat(callback.toString(), containsString(state));

        Throwable cause = new Throwable("abort");
        boolean aborted = callback.abort(cause);

        // Check abort in completed state
        if (state.equals("COMPLETE"))
        {
            assertThat(aborted, is(false));
            assertThat(onAbort.get(), nullValue());
            if (success)
            {
                assertThat(onCompleteFailure.get(), nullValue());
                assertTrue(onCompleteSuccess.get());
            }
            else
            {
                assertFalse(onCompleteSuccess.get());
                assertThat(onCompleteFailure.get(), notNullValue());
                assertTrue(ExceptionUtil.areAssociated(onCompleteFailure.get(), cause));
            }
            assertTrue(onCompleted.get());
            return;
        }

        // Check abort in non completed states
        if ((state.contains("CALLED") && !success) || state.equals("CLOSED"))
            assertThat(aborted, is(false));
        else
            assertThat(aborted, is(true));

        if (state.contains("PROCESSING"))
        {
            processLatch.countDown();

            Awaitility.waitAtMost(5, TimeUnit.SECONDS).pollInterval(10, TimeUnit.MILLISECONDS).until(() -> !callback.toString().contains("PROCESSING"));

            if (state.contains("CALLED"))
            {
                assertTrue(onCompleted.get());
            }

            if (action.equals("SCHEDULED"))
            {
                if (success)
                    callback.succeeded();
                else
                    callback.failed(new Throwable("failure after abort"));
            }
        }
        else if (state.contains("PENDING"))
        {
            if (success)
                callback.succeeded();
            else
                callback.failed(new Throwable("failure after abort"));
        }

        assertTrue(onCompleted.get());
        assertThat(onCompleteFailure.get(), notNullValue());

        if (callback.isAborted())
        {
            Throwable abort = onAbort.get();
            assertThat(abort, notNullValue());
            if (abort != cause)
            {
                assertThat(abort.getMessage(), is("abort in process"));
                assertTrue(ExceptionUtil.areAssociated(abort, cause));
            }
        }
    }

    private static class TestIteratingCB extends IteratingCallback
    {
        final AtomicInteger _count;
        final AtomicMarkableReference<Throwable> _completion = new AtomicMarkableReference<>(null, false);
        final CountDownLatch _completed = new CountDownLatch(2);

        private TestIteratingCB()
        {
            this(1);
        }

        private TestIteratingCB(int count)
        {
            _count = new AtomicInteger(count);
        }

        @Override
        protected Action process() throws Throwable
        {
            return _count.getAndDecrement() == 0 ? Action.SUCCEEDED : Action.SCHEDULED;
        }

        @Override
        protected void onAbort(Throwable cause)
        {
            _completion.compareAndSet(null, cause, false, false);
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            if (_completion.compareAndSet(null, cause, false, true))
                _completed.countDown();

            Throwable failure = _completion.getReference();
            if (failure != null && _completion.compareAndSet(failure, failure, false, true))
                _completed.countDown();
        }

        @Override
        protected void onCompleteSuccess()
        {
            if (_completion.compareAndSet(null, null, false, true))
                _completed.countDown();
        }

        @Override
        protected void onCompleted(Throwable causeOrNull)
        {
            _completed.countDown();
        }
    }
}
