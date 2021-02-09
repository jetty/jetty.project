//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util.thread;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <p>Convenience auto closeable {@link java.util.concurrent.locks.ReentrantLock} wrapper.</p>
 *
 * <pre>
 * try (Locker.Lock lock = locker.lock())
 * {
 *   // something
 * }
 * </pre>
 */
public class Locker
{
    private final ReentrantLock _lock = new ReentrantLock();
    private final Lock _unlock = new Lock();

    /**
     * <p>Acquires the lock.</p>
     *
     * @return the lock to unlock
     */
    public Lock lock()
    {
        _lock.lock();
        return _unlock;
    }

    /**
     * @return the lock to unlock
     * @deprecated use {@link #lock()} instead
     */
    @Deprecated
    public Lock lockIfNotHeld()
    {
        return lock();
    }

    /**
     * @return whether this lock has been acquired
     */
    public boolean isLocked()
    {
        return _lock.isLocked();
    }

    /**
     * @return a {@link Condition} associated with this lock
     */
    public Condition newCondition()
    {
        return _lock.newCondition();
    }

    /**
     * <p>The unlocker object that unlocks when it is closed.</p>
     */
    public class Lock implements AutoCloseable
    {
        @Override
        public void close()
        {
            _lock.unlock();
        }
    }

    @Deprecated
    public class UnLock extends Lock
    {
    }
}
