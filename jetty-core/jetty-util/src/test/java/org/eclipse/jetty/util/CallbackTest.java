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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.thread.Invocable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void testCallbackCollection()
    {
        FutureCallback mainCallback = new FutureCallback();
        List<Callback> callbacks = Callback.collection(mainCallback, 3);

        callbacks.get(0).succeeded();
        callbacks.get(0).succeeded();
        assertFalse(mainCallback.isDone());
        callbacks.get(1).succeeded();
        callbacks.get(1).succeeded();
        assertFalse(mainCallback.isDone());
        callbacks.get(2).succeeded();
        callbacks.get(2).succeeded();
        callbacks.forEach(Callback::succeeded);
        assertTrue(mainCallback.isDone());
    }

    @Test
    void testCallbackCollectionFailure()
    {
        FutureCallback mainCallback = new FutureCallback();
        List<Callback> callbacks = Callback.collection(mainCallback, 3);

        callbacks.get(0).succeeded();
        callbacks.get(0).succeeded();
        assertFalse(mainCallback.isDone());
        callbacks.get(1).failed(new Exception("Test Failure"));
        callbacks.get(1).succeeded();
        assertFalse(mainCallback.isDone());
        callbacks.get(2).succeeded();
        callbacks.get(2).succeeded();

        assertTrue(mainCallback.isDone());
        assertTrue(mainCallback.isFailed());
        assertThrows(ExecutionException.class, mainCallback::get);
    }

    @Test
    void testCallbackCollectionForcedFailure()
    {
        FutureCallback mainCallback = new FutureCallback();
        List<Callback> callbacks = Callback.collection(mainCallback, new Exception("Test Failure"), 3);

        callbacks.get(0).succeeded();
        callbacks.get(0).succeeded();
        assertFalse(mainCallback.isDone());
        callbacks.get(1).succeeded();
        callbacks.get(1).succeeded();
        assertFalse(mainCallback.isDone());
        callbacks.get(2).succeeded();
        callbacks.get(2).succeeded();

        assertTrue(mainCallback.isDone());
        assertTrue(mainCallback.isFailed());
        assertThrows(ExecutionException.class, mainCallback::get);
    }
}
