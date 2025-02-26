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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.thread.Invocable;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class CallbackTest
{
    // TODO Better coverage of Callback

    @Test
    void testNoOpCallback()
    {
        Callback callback = Callback.NOOP;
        assertEquals(Invocable.InvocationType.NON_BLOCKING, callback.getInvocationType());
        assertEquals("Callback.NOOP", callback.toString());

        assertDoesNotThrow(callback::succeeded);
        assertDoesNotThrow(() -> callback.failed(new Exception("Test")));
    }

    @Test
    void testCompleteWithSuccess()
    {
        CompletableFuture<Void> future = new CompletableFuture<>();
        FutureCallback callback = new FutureCallback();

        callback.completeWith(future);
        future.complete(null);

        assertTrue(callback.isDone());
        assertFalse(callback.isFailed());
    }

    @Test
    void testCompleteWithFailure()
    {
        CompletableFuture<Void> future = new CompletableFuture<>();
        FutureCallback callback = new FutureCallback();

        callback.completeWith(future);
        Exception failure = new Exception("Failure");
        future.completeExceptionally(failure);

        assertTrue(callback.isDone());
        assertTrue(callback.isFailed());
    }

    @Test
    void testCallbackFromCompletableFuture()
    {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Callback callback = Callback.from(future);

        callback.succeeded();
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
    }

    @Test
    void testCallbackFromCompletableFutureFailure()
    {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Callback callback = Callback.from(future);

        Exception failure = new Exception("Failure");
        callback.failed(failure);
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void testCallbackFromSuccessAndFailureHandlers()
    {
        AtomicBoolean successCalled = new AtomicBoolean(false);
        AtomicBoolean failureCalled = new AtomicBoolean(false);

        Callback callback = Callback.from(
            () -> successCalled.set(true),
            throwable -> failureCalled.set(true)
        );

        callback.succeeded();
        assertTrue(successCalled.get());
        assertFalse(failureCalled.get());

        successCalled.set(false);
        callback = Callback.from(
            () -> successCalled.set(true),
            throwable -> failureCalled.set(true)
        );
        callback.failed(new Exception("Test"));
        assertFalse(successCalled.get());
        assertTrue(failureCalled.get());
    }

    @Test
    void testNestedCallback()
    {
        AtomicBoolean innerCalled = new AtomicBoolean(false);
        AtomicBoolean outerCalled = new AtomicBoolean(false);

        Callback inner = Callback.from(() -> innerCalled.set(true));
        Callback nested = Callback.from(inner, () -> outerCalled.set(true));

        nested.succeeded();
        assertTrue(innerCalled.get());
        assertTrue(outerCalled.get());
    }

    @Test
    void testCombinedCallback()
    {
        AtomicBoolean firstCalled = new AtomicBoolean(false);
        AtomicBoolean secondCalled = new AtomicBoolean(false);

        Callback first = Callback.from(() -> firstCalled.set(true));
        Callback second = Callback.from(() -> secondCalled.set(true));

        Callback combined = Callback.from(first, second);
        combined.succeeded();

        assertTrue(firstCalled.get());
        assertTrue(secondCalled.get());
    }

    @Test
    void testCallbackCollectionAllSucceededBefore()
    {
        FutureCallback mainCallback = new FutureCallback();
        try (Callback.Combination combination = new Callback.Combination(mainCallback))
        {
            for (int i = 0; i < 10; i++)
                combination.newCallback().succeeded();
        }
        assertTrue(mainCallback.isDone());
        assertFalse(mainCallback.isFailed());
    }

    @Test
    void testCallbackCollectionOneFailBefore() throws Exception
    {
        FutureCallback mainCallback = new FutureCallback();
        Exception failure = new Exception("Test");
        try (Callback.Combination combination = new Callback.Combination(mainCallback))
        {
            for (int i = 0; i < 5; i++)
                combination.newCallback().succeeded();
            combination.newCallback().failed(failure);
            for (int i = 6; i < 10; i++)
                combination.newCallback().succeeded();
        }

        assertTrue(mainCallback.isDone());
        assertTrue(mainCallback.isFailed());
        try
        {
            mainCallback.get();
            fail();
        }
        catch (ExecutionException e)
        {
            assertThat(e.getCause(), Matchers.sameInstance(failure));
        }
    }

    @Test
    void testCallbackCollectionAllSucceededAfter()
    {
        FutureCallback mainCallback = new FutureCallback();
        Callback[] callbacks = new Callback[10];
        try (Callback.Combination combination = new Callback.Combination(mainCallback))
        {
            for (int i = 0; i < callbacks.length; i++)
                callbacks[i] = combination.newCallback();
        }
        assertFalse(mainCallback.isDone());

        for (Callback callback : callbacks)
            callback.succeeded();
        assertTrue(mainCallback.isDone());
        assertFalse(mainCallback.isFailed());
    }

    @Test
    void testCallbackCollectionOneFailAfter() throws InterruptedException
    {
        FutureCallback mainCallback = new FutureCallback();
        Callback[] callbacks = new Callback[10];
        try (Callback.Combination combination = new Callback.Combination(mainCallback))
        {
            for (int i = 0; i < callbacks.length; i++)
                callbacks[i] = combination.newCallback();
        }
        assertFalse(mainCallback.isDone());

        for (int i = 0; i < 5; i++)
            callbacks[i].succeeded();
        Exception failure = new Exception("Test");
        callbacks[5].failed(failure);
        for (int i = 6; i < 10; i++)
            callbacks[i].succeeded();

        assertTrue(mainCallback.isDone());
        assertTrue(mainCallback.isFailed());
        try
        {
            mainCallback.get();
            fail();
        }
        catch (ExecutionException e)
        {
            assertThat(e.getCause(), Matchers.sameInstance(failure));
        }
    }

    @Test
    void testCallbackCollectionForceFail() throws InterruptedException
    {
        FutureCallback mainCallback = new FutureCallback();
        Exception failure = new Exception("Test");
        try (Callback.Combination combination = new Callback.Combination(mainCallback, failure))
        {
            for (int i = 0; i < 10; i++)
                combination.newCallback().succeeded();
        }
        assertTrue(mainCallback.isDone());
        assertTrue(mainCallback.isFailed());
        try
        {
            mainCallback.get();
            fail();
        }
        catch (ExecutionException e)
        {
            assertThat(e.getCause(), Matchers.sameInstance(failure));
        }
    }

    @Test
    void testCallbackCollectionForceFailAssociated() throws InterruptedException
    {
        FutureCallback mainCallback = new FutureCallback();
        Exception failure = new Exception("Test");
        Exception associated = new Exception("Test");
        try (Callback.Combination combination = new Callback.Combination(mainCallback, failure))
        {
            for (int i = 0; i < 5; i++)
                combination.newCallback().succeeded();
            combination.newCallback().failed(associated);
            for (int i = 6; i < 10; i++)
                combination.newCallback().succeeded();
        }
        assertTrue(mainCallback.isDone());
        assertTrue(mainCallback.isFailed());
        try
        {
            mainCallback.get();
            fail();
        }
        catch (ExecutionException e)
        {
            assertThat(e.getCause(), Matchers.sameInstance(failure));
            assertTrue(ExceptionUtil.areAssociated(failure, associated));
        }
    }
}
