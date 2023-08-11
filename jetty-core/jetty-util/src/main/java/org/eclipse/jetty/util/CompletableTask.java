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

/**
 * <p>A {@link CompletableFuture} that implements {@link Runnable} to perform
 * a one-shot task that eventually completes this {@link CompletableFuture}.</p>
 * <p>Subclasses override {@link #run()} to implement the task.</p>
 * <p>Users of this class start the task execution via {@link #start()}.</p>
 * <p>Typical usage:</p>
 * <pre>{@code
 * CompletableTask<T> task = new CompletableTask<>()
 * {
 *     @Override
 *     public void run()
 *     {
 *         try
 *         {
 *             // Perform some task.
 *             T result = performTask();
 *
 *             // Eventually complete this CompletableFuture.
 *             complete(result);
 *         }
 *         catch (Throwable x)
 *         {
 *             completeExceptionally(x);
 *         }
 *     }
 * }
 *
 * // Start the task and then process the
 * // result of the task when it is complete.
 * task.start()
 *     .whenComplete((result, failure) ->
 *     {
 *         if (failure == null)
 *         {
 *             // The task completed successfully.
 *         }
 *         else
 *         {
 *             // The task failed.
 *         }
 *     });
 * }</pre>
 *
 * @param <T> the type of the result of the task
 */
public abstract class CompletableTask<T> extends CompletableFuture<T> implements Runnable
{
    /**
     * <p>Starts the task by calling {@link #run()}
     * and returns this {@link CompletableTask}.</p>
     *
     * @return this {@link CompletableTask}
     */
    public CompletableTask<T> start()
    {
        run();
        return this;
    }
}
