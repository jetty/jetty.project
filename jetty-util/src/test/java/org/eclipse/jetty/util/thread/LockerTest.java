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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LockerTest
{
    public LockerTest()
    {
    }

    @Test
    public void testLocked()
    {
        Locker lock = new Locker();
        assertFalse(lock.isLocked());

        try (Locker.Lock l = lock.lock())
        {
            assertTrue(lock.isLocked());
        }
        finally
        {
            assertFalse(lock.isLocked());
        }

        assertFalse(lock.isLocked());
    }

    @Test
    public void testLockedException()
    {
        Locker lock = new Locker();
        assertFalse(lock.isLocked());

        try (Locker.Lock l = lock.lock())
        {
            assertTrue(lock.isLocked());
            throw new Exception();
        }
        catch (Exception e)
        {
            assertFalse(lock.isLocked());
        }
        finally
        {
            assertFalse(lock.isLocked());
        }

        assertFalse(lock.isLocked());
    }

    @Test
    public void testContend() throws Exception
    {
        final Locker lock = new Locker();

        final CountDownLatch held0 = new CountDownLatch(1);
        final CountDownLatch hold0 = new CountDownLatch(1);

        Thread thread0 = new Thread()
        {
            @Override
            public void run()
            {
                try (Locker.Lock l = lock.lock())
                {
                    held0.countDown();
                    hold0.await();
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }
        };
        thread0.start();
        held0.await();

        assertTrue(lock.isLocked());

        final CountDownLatch held1 = new CountDownLatch(1);
        final CountDownLatch hold1 = new CountDownLatch(1);
        Thread thread1 = new Thread()
        {
            @Override
            public void run()
            {
                try (Locker.Lock l = lock.lock())
                {
                    held1.countDown();
                    hold1.await();
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }
        };
        thread1.start();
        // thread1 will be spinning here
        assertFalse(held1.await(100, TimeUnit.MILLISECONDS));

        // Let thread0 complete
        hold0.countDown();
        thread0.join();

        // thread1 can progress
        held1.await();

        // let thread1 complete
        hold1.countDown();
        thread1.join();

        assertFalse(lock.isLocked());
    }
}
