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

package org.eclipse.jetty.io;

import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.NanoTime;
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

        long start = NanoTime.now();
        while (_expired == null && NanoTime.secondsElapsedFrom(start) < 5)
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
