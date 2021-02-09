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

package org.eclipse.jetty.io;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.thread.TimerScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class IdleTimeoutTest
{
    volatile boolean _open;
    volatile TimeoutException _expired;

    TimerScheduler _timer;
    IdleTimeout _timeout;

    @BeforeEach
    public void setUp() throws Exception
    {
        _open = true;
        _expired = null;
        _timer = new TimerScheduler();
        _timer.start();
        _timeout = new IdleTimeout(_timer)
        {
            @Override
            protected void onIdleExpired(TimeoutException timeout)
            {
                _expired = timeout;
            }

            @Override
            public boolean isOpen()
            {
                return _open;
            }
        };
        _timeout.setIdleTimeout(1000);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _open = false;
        _timer.stop();
    }

    @Test
    public void testNotIdle() throws Exception
    {
        for (int i = 0; i < 20; i++)
        {
            Thread.sleep(100);
            _timeout.notIdle();
        }

        assertNull(_expired);
    }

    @Test
    public void testIdle() throws Exception
    {
        for (int i = 0; i < 5; i++)
        {
            Thread.sleep(100);
            _timeout.notIdle();
        }
        Thread.sleep(1500);
        assertNotNull(_expired);
    }

    @Test
    public void testClose() throws Exception
    {
        for (int i = 0; i < 5; i++)
        {
            Thread.sleep(100);
            _timeout.notIdle();
        }
        _timeout.onClose();
        Thread.sleep(1500);
        assertNull(_expired);
    }

    @Test
    public void testClosed() throws Exception
    {
        for (int i = 0; i < 5; i++)
        {
            Thread.sleep(100);
            _timeout.notIdle();
        }
        _open = false;
        Thread.sleep(1500);
        assertNull(_expired);
    }

    @Test
    public void testShorten() throws Exception
    {
        _timeout.setIdleTimeout(2000);

        for (int i = 0; i < 30; i++)
        {
            Thread.sleep(100);
            _timeout.notIdle();
        }
        assertNull(_expired);
        _timeout.setIdleTimeout(100);

        long start = System.nanoTime();
        while (_expired == null && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
        {
            Thread.sleep(200);
        }

        assertNotNull(_expired);
    }

    @Test
    public void testLengthen() throws Exception
    {
        for (int i = 0; i < 5; i++)
        {
            Thread.sleep(100);
            _timeout.notIdle();
        }
        _timeout.setIdleTimeout(10000);
        Thread.sleep(1500);
        assertNull(_expired);
    }

    @Test
    public void testMultiple() throws Exception
    {
        Thread.sleep(1500);
        assertNotNull(_expired);
        _expired = null;
        Thread.sleep(1000);
        assertNotNull(_expired);
    }
}
