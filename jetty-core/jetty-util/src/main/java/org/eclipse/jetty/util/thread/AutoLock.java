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

import java.io.Serial;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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
    @Serial
    private static final long serialVersionUID = 3300696774541816341L;

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
     * <p>Tries to acquire the lock.</p>
     * <p>Whether the lock was acquired can be tested
     * with {@link #isHeldByCurrentThread()}.</p>
     * <p>Typical usage of this method is in {@code toString()},
     * to avoid deadlocks when the implementation needs to lock
     * to retrieve a consistent state to produce the string.</p>
     *
     * @return this AutoLock for unlocking
     */
    public AutoLock tryLock()
    {
        _lock.tryLock();
        return this;
    }

    /**
     * @return whether this lock is held by the current thread
     * @see ReentrantLock#isHeldByCurrentThread()
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
        if (isHeldByCurrentThread())
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

        @Override
        public AutoLock.WithCondition tryLock()
        {
            return (WithCondition)super.tryLock();
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
         * @throws InterruptedException if the current thread is interrupted
         * @see Condition#await()
         */
        public void await() throws InterruptedException
        {
            _condition.await();
        }

        /**
         * @param time the time to wait
         * @param unit the time unit
         * @return false if the waiting time elapsed
         * @throws InterruptedException if the current thread is interrupted
         * @see Condition#await(long, TimeUnit)
         */
        public boolean await(long time, TimeUnit unit) throws InterruptedException
        {
            return _condition.await(time, unit);
        }
    }
}
