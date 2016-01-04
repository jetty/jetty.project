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

package org.eclipse.jetty.io;


import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.thread.TimerScheduler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class IdleTimeoutTest
{
    volatile boolean _open;
    volatile TimeoutException _expired;

    TimerScheduler _timer;
    IdleTimeout _timeout;

    @Before
    public void setUp() throws Exception
    {
        _open=true;
        _expired=null;
        _timer=new TimerScheduler();
        _timer.start();
        _timeout=new IdleTimeout(_timer)
        {
            @Override
            protected void onIdleExpired(TimeoutException timeout)
            {
                _expired=timeout;
            }

            @Override
            public boolean isOpen()
            {
                return _open;
            }
        };
        _timeout.setIdleTimeout(1000);
    }

    @After
    public void tearDown() throws Exception
    {
        _open=false;
        _timer.stop();

    }

    @Test
    public void testNotIdle() throws Exception
    {
        for (int i=0;i<20;i++)
        {
            Thread.sleep(100);
            _timeout.notIdle();
        }

        Assert.assertNull(_expired);
    }

    @Test
    public void testIdle() throws Exception
    {
        for (int i=0;i<5;i++)
        {
            Thread.sleep(100);
            _timeout.notIdle();
        }
        Thread.sleep(1500);
        Assert.assertNotNull(_expired);
    }

    @Test
    public void testClose() throws Exception
    {
        for (int i=0;i<5;i++)
        {
            Thread.sleep(100);
            _timeout.notIdle();
        }
        _timeout.onClose();
        Thread.sleep(1500);
        Assert.assertNull(_expired);
    }

    @Test
    public void testClosed() throws Exception
    {
        for (int i=0;i<5;i++)
        {
            Thread.sleep(100);
            _timeout.notIdle();
        }
        _open=false;
        Thread.sleep(1500);
        Assert.assertNull(_expired);
    }

    @Test
    public void testShorten() throws Exception
    {
        for (int i=0;i<5;i++)
        {
            Thread.sleep(100);
            _timeout.notIdle();
        }
        _timeout.setIdleTimeout(100);
        Thread.sleep(400);
        Assert.assertNotNull(_expired);
    }

    @Test
    public void testLengthen() throws Exception
    {
        for (int i=0;i<5;i++)
        {
            Thread.sleep(100);
            _timeout.notIdle();
        }
        _timeout.setIdleTimeout(10000);
        Thread.sleep(1500);
        Assert.assertNull(_expired);
    }

    @Test
    public void testMultiple() throws Exception
    {
        Thread.sleep(1500);
        Assert.assertNotNull(_expired);
        _expired=null;
        Thread.sleep(1000);
        Assert.assertNotNull(_expired);
    }



}
