//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.atomic.AtomicBoolean;


/* ------------------------------------------------------------ */
/** Spin Lock
 * <p>This is a lock designed to protect VERY short sections of 
 * critical code.  Threads attempting to take the lock will spin 
 * forever until the lock is available, thus it is important that
 * the code protected by this lock is extremely simple and non
 * blocking. The reason for this lock is that it prevents a thread
 * from giving up a CPU core when contending for the lock.</p>
 * <pre>
 * try(SpinLock.Lock lock = spinlock.lock())
 * {
 *   // something very quick and non blocking
 * }
 * </pre>
 */
public class SpinLock
{
    private final AtomicBoolean _lock = new AtomicBoolean(false);
    private final Lock _unlock = new Lock();
    
    public Lock lock()
    {
        while(true)
        {
            if (!_lock.compareAndSet(false,true))
            {
                continue;
            }
            return _unlock;
        }
    }
    
    public boolean isLocked()
    {
        return _lock.get();
    }
    
    public class Lock implements AutoCloseable
    {
        @Override
        public void close()
        {
            if (!_lock.compareAndSet(true,false))
                throw new IllegalStateException();
        }
    }
}
