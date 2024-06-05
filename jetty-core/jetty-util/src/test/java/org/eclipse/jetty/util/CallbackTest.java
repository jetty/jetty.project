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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicMarkableReference;

import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CallbackTest
{
    private Scheduler scheduler;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        scheduler = new ScheduledExecutorScheduler();
        scheduler.start();
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        scheduler.stop();
    }

    @Test
    public void testCompletingSuccess() throws Exception
    {
        TestCompletingCB callback = new TestCompletingCB();
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
    public void testCompletingFailure() throws Exception
    {
        Throwable failure = new Throwable();
        TestCompletingCB callback = new TestCompletingCB();
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
    public void testCompletingAbortSuccess() throws Exception
    {
        TestCompletingCB callback = new TestCompletingCB();

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

    @Test
    public void testCompletingAbortFailure() throws Exception
    {
        TestCompletingCB callback = new TestCompletingCB();

        Throwable abort = new Throwable();
        callback.abort(abort);
        assertFalse(callback._completed.await(100, TimeUnit.MILLISECONDS));
        assertThat(callback._completion.getReference(), Matchers.sameInstance(abort));
        assertFalse(callback._completion.isMarked());

        Throwable failure = new Throwable();
        callback.failed(failure);
        assertThat(callback._completion.getReference(), Matchers.sameInstance(abort));
        assertFalse(ExceptionUtil.areNotAssociated(abort, failure));
        assertThat(callback._completed.getCount(), is(0L));

        Throwable late = new Throwable();
        callback.failed(late);
        assertFalse(callback.abort(late));
        assertFalse(ExceptionUtil.areNotAssociated(abort, late));
        assertThat(callback._completion.getReference(), Matchers.sameInstance(abort));
        assertThat(callback._completed.getCount(), is(0L));
    }

    @Test
    public void testCombineSuccess() throws Exception
    {
        TestCompletingCB callbackA = new TestCompletingCB();
        TestCompletingCB callbackB = new TestCompletingCB();
        Callback combined = Callback.combine(callbackA, callbackB);

        combined.succeeded();
        assertTrue(callbackA._completed.await(1, TimeUnit.SECONDS));
        assertThat(callbackA._completion.getReference(), Matchers.nullValue());
        assertTrue(callbackA._completion.isMarked());

        assertTrue(callbackB._completed.await(1, TimeUnit.SECONDS));
        assertThat(callbackB._completion.getReference(), Matchers.nullValue());
        assertTrue(callbackB._completion.isMarked());
    }

    @Test
    public void testCombineFailure() throws Exception
    {
        TestCompletingCB callbackA = new TestCompletingCB();
        TestCompletingCB callbackB = new TestCompletingCB();
        Callback combined = Callback.combine(callbackA, callbackB);

        Throwable failure = new Throwable();
        combined.failed(failure);

        assertTrue(callbackA._completed.await(1, TimeUnit.SECONDS));
        assertThat(callbackA._completion.getReference(), Matchers.sameInstance(failure));
        assertTrue(callbackA._completion.isMarked());

        assertTrue(callbackB._completed.await(1, TimeUnit.SECONDS));
        assertThat(callbackB._completion.getReference(), Matchers.sameInstance(failure));
        assertTrue(callbackB._completion.isMarked());
    }

    @Test
    public void testCombineAbortSuccess() throws Exception
    {
        TestCompletingCB callbackA = new TestCompletingCB();
        TestCompletingCB callbackB = new TestCompletingCB();
        Callback combined = Callback.combine(callbackA, callbackB);

        Throwable abort = new Throwable();
        combined.abort(abort);
        assertFalse(callbackA._completed.await(100, TimeUnit.MILLISECONDS));
        assertThat(callbackA._completion.getReference(), Matchers.sameInstance(abort));
        assertFalse(callbackA._completion.isMarked());
        assertFalse(callbackB._completed.await(100, TimeUnit.MILLISECONDS));
        assertThat(callbackB._completion.getReference(), Matchers.sameInstance(abort));
        assertFalse(callbackB._completion.isMarked());

        combined.succeeded();
        assertThat(callbackA._completion.getReference(), Matchers.sameInstance(abort));
        assertThat(callbackA._completed.getCount(), is(0L));
        assertThat(callbackB._completion.getReference(), Matchers.sameInstance(abort));
        assertThat(callbackB._completed.getCount(), is(0L));
    }

    @Test
    public void testCombineAbortFailure() throws Exception
    {
        TestCompletingCB callbackA = new TestCompletingCB();
        TestCompletingCB callbackB = new TestCompletingCB();
        Callback combined = Callback.combine(callbackA, callbackB);

        Throwable abort = new Throwable();
        combined.abort(abort);
        assertFalse(callbackA._completed.await(100, TimeUnit.MILLISECONDS));
        assertThat(callbackA._completion.getReference(), Matchers.sameInstance(abort));
        assertFalse(callbackA._completion.isMarked());
        assertFalse(callbackB._completed.await(100, TimeUnit.MILLISECONDS));
        assertThat(callbackB._completion.getReference(), Matchers.sameInstance(abort));
        assertFalse(callbackB._completion.isMarked());

        Throwable failure = new Throwable();
        combined.failed(failure);
        assertFalse(ExceptionUtil.areNotAssociated(abort, failure));
        assertThat(callbackA._completion.getReference(), Matchers.sameInstance(abort));
        assertThat(callbackA._completed.getCount(), is(0L));
        assertThat(callbackB._completion.getReference(), Matchers.sameInstance(abort));
        assertThat(callbackB._completed.getCount(), is(0L));
    }

    @Test
    public void testNestedSuccess() throws Exception
    {
        TestCompletingCB callback = new TestCompletingCB();
        Callback nested = new Callback.Nested(callback);
        nested.succeeded();
        assertTrue(callback._completed.await(1, TimeUnit.SECONDS));
        assertThat(callback._completion.getReference(), Matchers.nullValue());
        assertTrue(callback._completion.isMarked());
    }

    @Test
    public void testNestedFailure() throws Exception
    {
        Throwable failure = new Throwable();
        TestCompletingCB callback = new TestCompletingCB();
        Callback nested = new Callback.Nested(callback);
        nested.failed(failure);
        assertTrue(callback._completed.await(1, TimeUnit.SECONDS));
        assertThat(callback._completion.getReference(), Matchers.sameInstance(failure));
        assertTrue(callback._completion.isMarked());
    }

    @Test
    public void testNestedAbortSuccess() throws Exception
    {
        TestCompletingCB callback = new TestCompletingCB();
        Callback nested = new Callback.Nested(callback);

        Throwable abort = new Throwable();
        nested.abort(abort);
        assertFalse(callback._completed.await(100, TimeUnit.MILLISECONDS));
        assertThat(callback._completion.getReference(), Matchers.sameInstance(abort));
        assertFalse(callback._completion.isMarked());

        nested.succeeded();
        assertThat(callback._completion.getReference(), Matchers.sameInstance(abort));
        assertThat(callback._completed.getCount(), is(0L));
    }

    @Test
    public void testNestedAbortFailure() throws Exception
    {
        TestCompletingCB callback = new TestCompletingCB();
        Callback nested = new Callback.Nested(callback);

        Throwable abort = new Throwable();
        nested.abort(abort);
        assertFalse(callback._completed.await(100, TimeUnit.MILLISECONDS));
        assertThat(callback._completion.getReference(), Matchers.sameInstance(abort));
        assertFalse(callback._completion.isMarked());

        Throwable failure = new Throwable();
        nested.failed(failure);
        assertThat(callback._completion.getReference(), Matchers.sameInstance(abort));
        assertFalse(ExceptionUtil.areNotAssociated(abort, failure));
        assertThat(callback._completed.getCount(), is(0L));
    }

    @Test
    public void testAbortingWrappedByLegacyCallback() throws Exception
    {
        TestCompletingCB callback = new TestCompletingCB();
        Callback legacyCb = new Callback()
        {
            @Override
            public void succeeded()
            {
                callback.succeeded();
            }

            @Override
            public void failed(Throwable cause)
            {
                callback.failed(cause);
            }

            // TODO uncommenting this fixes the test
//            @Override
//            public boolean abort(Throwable cause)
//            {
//                return callback.abort(cause);
//            }
        };

        Throwable abort = new Throwable();
        legacyCb.abort(abort);
        assertFalse(callback._completed.await(100, TimeUnit.MILLISECONDS));
        assertThat(callback._completion.getReference(), Matchers.sameInstance(abort));
        assertFalse(callback._completion.isMarked());

        Throwable failure = new Throwable();
        legacyCb.failed(failure);
        assertThat(callback._completion.getReference(), Matchers.sameInstance(abort));
        assertFalse(ExceptionUtil.areNotAssociated(abort, failure));
        assertThat(callback._completed.getCount(), is(0L));
    }

    private static class TestCompletingCB extends Callback.Completing
    {
        final AtomicMarkableReference<Throwable> _completion = new AtomicMarkableReference<>(null, false);
        final CountDownLatch _completed = new CountDownLatch(2);

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
        protected void completed()
        {
            _completed.countDown();
        }
    }
}
