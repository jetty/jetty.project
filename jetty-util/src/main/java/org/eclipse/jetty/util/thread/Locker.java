//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
 * Convenience Lock Wrapper.
 * 
 * <pre>
 * try(SpinLock.Lock lock = locker.lock())
 * {
 *   // something very quick and non blocking
 * }
 * </pre>
 */
public class Locker
{
    private final ReentrantLock _lock = new ReentrantLock();
    private final Lock _unlock = new Lock();

    public Locker()
    {
    }

    public Lock lock()
    {
        if (_lock.isHeldByCurrentThread())
            throw new IllegalStateException("Locker is not reentrant");
        _lock.lock();
        return _unlock;
    }

    public boolean isLocked()
    {
        return _lock.isLocked();
    }

    public class Lock implements AutoCloseable
    {
        @Override
        public void close()
        {
            _lock.unlock();
        }
    }
    
    public Condition newCondition()
    {
        return _lock.newCondition();
    }
}
