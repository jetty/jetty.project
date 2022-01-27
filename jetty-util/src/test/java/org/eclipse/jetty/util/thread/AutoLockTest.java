//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AutoLockTest
{
    @Test
    public void testLocked()
    {
        AutoLock lock = new AutoLock();
        assertFalse(lock.isLocked());

        try (AutoLock l = lock.lock())
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
        AutoLock lock = new AutoLock();
        assertFalse(lock.isLocked());

        try (AutoLock l = lock.lock())
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
        AutoLock lock = new AutoLock();

        final CountDownLatch held0 = new CountDownLatch(1);
        final CountDownLatch hold0 = new CountDownLatch(1);

        Thread thread0 = new Thread(() ->
        {
            try (AutoLock l = lock.lock())
            {
                held0.countDown();
                hold0.await();
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        });
        thread0.start();
        held0.await();

        assertTrue(lock.isLocked());

        final CountDownLatch held1 = new CountDownLatch(1);
        final CountDownLatch hold1 = new CountDownLatch(1);
        Thread thread1 = new Thread(() ->
        {
            try (AutoLock l = lock.lock())
            {
                held1.countDown();
                hold1.await();
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        });
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
