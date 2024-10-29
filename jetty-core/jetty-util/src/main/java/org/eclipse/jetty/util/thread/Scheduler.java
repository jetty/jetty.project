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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.component.LifeCycle;

/**
 * <p>Schedules tasks to be executed after a delay.</p>
 */
public interface Scheduler extends LifeCycle
{
    /**
     * <p>A delayed task that can be cancelled.</p>
     */
    interface Task
    {
        /**
         * <p>Attempts to cancel the execution of this task.</p>
         * <p>If the task is already cancelled, or already executed,
         * this method has no effect and returns {@code false}.</p>
         * <p>Otherwise, the execution of this task is cancelled
         * and this method returns {@code true}.</p>
         *
         * @return whether the task was cancelled
         */
        boolean cancel();
    }

    /**
     * <p>Schedules a task to be executed after the given delay.</p>
     *
     * @param task the task to execute
     * @param delay the delay value
     * @param units the unit of time of the delay
     * @return a delayed task
     */
    Task schedule(Runnable task, long delay, TimeUnit units);

    /**
     * <p>Schedules a task to be executed after the given delay.</p>
     *
     * @param task the task to execute
     * @param delay the delay duration
     * @return a delayed task
     */
    default Task schedule(Runnable task, Duration delay)
    {
        return schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
    }
}
