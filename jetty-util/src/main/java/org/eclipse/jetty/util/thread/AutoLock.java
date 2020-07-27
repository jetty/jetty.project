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

import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * <p>Reentrant lock that can be used in a try-with-resources statement.</p>
 * <p>Typical usage:</p>
 * <pre>
 * try (AutoLock lock = this.lock.lock())
 * {
 *     // Something
 * }
 * </pre>
 */
public class AutoLock implements AutoCloseable, Serializable
{
    private final ReentrantLock _lock = new ReentrantLock();

    /**
     * <p>Acquires the lock.</p>
     *
     * @return this AutoLock for unlocking
     */
    public AutoLock lock()
    {
        _lock.lock();
        return this;
    }

    /**
     * <p>Runs the given code with the lock held.</p>
     * <p>This is equivalent to:</p>
     * <pre>
     * try (AutoLock ignored = lock())
     * {
     *     code.run();
     * }
     * </pre>
     *
     * @param code the code to run with the lock held.
     */
    public void runLocked(Runnable code)
    {
        try (AutoLock ignored = lock())
        {
            code.run();
        }
    }

    /**
     * <p>Returns the result of running the given code with the lock held.</p>
     * <p>This is equivalent to:</p>
     * <pre>
     * try (AutoLock ignored = lock())
     * {
     *     return code.get();
     * }
     * </pre>
     *
     * @param code the code to run with the lock held.
     * @param <T> the result type
     * @return the result of the code run
     */
    public <T> T runLocked(Supplier<T> code)
    {
        try (AutoLock ignored = lock())
        {
            return code.get();
        }
    }

    /**
     * @see ReentrantLock#isHeldByCurrentThread()
     * @return whether this lock is held by the current thread
     */
    public boolean isHeldByCurrentThread()
    {
        return _lock.isHeldByCurrentThread();
    }

    /**
     * @return a {@link Condition} associated with this lock
     */
    public Condition newCondition()
    {
        return _lock.newCondition();
    }

    // Package-private for testing only.
    boolean isLocked()
    {
        return _lock.isLocked();
    }

    @Override
    public void close()
    {
        _lock.unlock();
    }

    /**
     * <p>A reentrant lock with a condition that can be used in a try-with-resources statement.</p>
     * <p>Typical usage:</p>
     * <pre>
     * // Waiting
     * try (AutoLock lock = _lock.lock())
     * {
     *     lock.await();
     * }
     *
     * // Signaling
     * try (AutoLock lock = _lock.lock())
     * {
     *     lock.signalAll();
     * }
     * </pre>
     */
    public static class WithCondition extends AutoLock
    {
        private final Condition _condition = newCondition();

        @Override
        public AutoLock.WithCondition lock()
        {
            return (WithCondition)super.lock();
        }

        /**
         * @see Condition#signal()
         */
        public void signal()
        {
            _condition.signal();
        }

        /**
         * @see Condition#signalAll()
         */
        public void signalAll()
        {
            _condition.signalAll();
        }

        /**
         * @see Condition#await()
         * @throws InterruptedException if the current thread is interrupted
         */
        public void await() throws InterruptedException
        {
            _condition.await();
        }

        /**
         * @see Condition#await(long, TimeUnit)
         * @param time the time to wait
         * @param unit the time unit
         * @return false if the waiting time elapsed
         * @throws InterruptedException if the current thread is interrupted
         */
        public boolean await(long time, TimeUnit unit) throws InterruptedException
        {
            return _condition.await(time, unit);
        }
    }
}
