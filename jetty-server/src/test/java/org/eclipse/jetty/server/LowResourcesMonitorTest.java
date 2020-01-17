//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LowResourcesMonitorTest
{
    QueuedThreadPool _threadPool;
    Server _server;
    ServerConnector _connector;
    LowResourceMonitor _lowResourcesMonitor;

    @BeforeEach
    public void before() throws Exception
    {
        _threadPool = new QueuedThreadPool();
        _threadPool.setMaxThreads(50);

        _server = new Server(_threadPool);
        _server.manage(_threadPool);

        _server.addBean(new TimerScheduler());

        _connector = new ServerConnector(_server);
        _connector.setPort(0);
        _connector.setIdleTimeout(35000);
        _server.addConnector(_connector);

        _server.setHandler(new DumpHandler());

        _lowResourcesMonitor = new LowResourceMonitor(_server);
        _lowResourcesMonitor.setLowResourcesIdleTimeout(200);
        _lowResourcesMonitor.setMaxConnections(20);
        _lowResourcesMonitor.setPeriod(900);
        _lowResourcesMonitor.setMonitoredConnectors(Collections.singleton(_connector));
        _server.addBean(_lowResourcesMonitor);

        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testLowOnThreads() throws Exception
    {
        _lowResourcesMonitor.setMonitorThreads(true);
        Thread.sleep(1200);
        _threadPool.setMaxThreads(_threadPool.getThreads() - _threadPool.getIdleThreads() + 10);
        Thread.sleep(1200);
        assertFalse(_lowResourcesMonitor.isLowOnResources(), _lowResourcesMonitor.getReasons());

        final CountDownLatch latch = new CountDownLatch(1);

        for (int i = 0; i < 100; i++)
        {
            _threadPool.execute(() ->
            {
                try
                {
                    latch.await();
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            });
        }

        Thread.sleep(1200);
        assertTrue(_lowResourcesMonitor.isLowOnResources());

        latch.countDown();
        Thread.sleep(1200);

        assertFalse(_lowResourcesMonitor.isLowOnResources(), _lowResourcesMonitor.getReasons());
    }

    @Test
    public void testNotAccepting() throws Exception
    {
        _lowResourcesMonitor.setAcceptingInLowResources(false);
        _lowResourcesMonitor.setMonitorThreads(true);
        Thread.sleep(1200);
        int maxThreads = _threadPool.getThreads() - _threadPool.getIdleThreads() + 10;
        System.out.println("maxThreads:" + maxThreads);
        _threadPool.setMaxThreads(maxThreads);
        Thread.sleep(1200);
        assertFalse(_lowResourcesMonitor.isLowOnResources(), _lowResourcesMonitor.getReasons());

        for (AbstractConnector c : _server.getBeans(AbstractConnector.class))
        {
            assertThat(c.isAccepting(), Matchers.is(true));
        }

        final CountDownLatch latch = new CountDownLatch(1);
        for (int i = 0; i < 100; i++)
        {
            _threadPool.execute(() ->
            {
                try
                {
                    latch.await();
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            });
        }

        Thread.sleep(1200);
        assertTrue(_lowResourcesMonitor.isLowOnResources());
        for (AbstractConnector c : _server.getBeans(AbstractConnector.class))
        {
            assertThat(c.isAccepting(), Matchers.is(false));
        }

        latch.countDown();
        Thread.sleep(1200);
        assertFalse(_lowResourcesMonitor.isLowOnResources(), _lowResourcesMonitor.getReasons());
        for (AbstractConnector c : _server.getBeans(AbstractConnector.class))
        {
            assertThat(c.isAccepting(), Matchers.is(true));
        }
    }

    @Disabled("not reliable")
    @Test
    public void testLowOnMemory() throws Exception
    {
        _lowResourcesMonitor.setMaxMemory(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() + (100 * 1024 * 1024));
        Thread.sleep(1200);
        assertFalse(_lowResourcesMonitor.isLowOnResources(), _lowResourcesMonitor.getReasons());

        byte[] data = new byte[100 * 1024 * 1024];
        Arrays.fill(data, (byte)1);
        int hash = Arrays.hashCode(data);
        assertThat(hash, not(equalTo(0)));

        Thread.sleep(1200);
        assertTrue(_lowResourcesMonitor.isLowOnResources());
        data = null;
        System.gc();
        System.gc();

        Thread.sleep(1200);
        assertFalse(_lowResourcesMonitor.isLowOnResources(), _lowResourcesMonitor.getReasons());
    }

    @Test
    public void testMaxConnectionsAndMaxIdleTime() throws Exception
    {
        _lowResourcesMonitor.setMaxMemory(0);
        assertFalse(_lowResourcesMonitor.isLowOnResources(), _lowResourcesMonitor.getReasons());

        assertEquals(20, _lowResourcesMonitor.getMaxConnections());
        Socket[] socket = new Socket[_lowResourcesMonitor.getMaxConnections() + 1];
        for (int i = 0; i < socket.length; i++)
        {
            socket[i] = new Socket("localhost", _connector.getLocalPort());
        }

        Thread.sleep(1200);
        assertTrue(_lowResourcesMonitor.isLowOnResources());

        try (Socket newSocket = new Socket("localhost", _connector.getLocalPort()))
        {
            // wait for low idle time to close sockets, but not new Socket
            Thread.sleep(1200);
            assertFalse(_lowResourcesMonitor.isLowOnResources(), _lowResourcesMonitor.getReasons());

            for (int i = 0; i < socket.length; i++)
            {
                assertEquals(-1, socket[i].getInputStream().read());
            }

            newSocket.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            assertEquals('H', newSocket.getInputStream().read());
        }
    }

    @Test
    public void testMaxLowResourcesTime() throws Exception
    {
        int monitorPeriod = _lowResourcesMonitor.getPeriod();
        int lowResourcesIdleTimeout = _lowResourcesMonitor.getLowResourcesIdleTimeout();
        assertThat(lowResourcesIdleTimeout, Matchers.lessThan(monitorPeriod));

        int maxLowResourcesTime = 5 * monitorPeriod;
        _lowResourcesMonitor.setMaxLowResourcesTime(maxLowResourcesTime);
        assertFalse(_lowResourcesMonitor.isLowOnResources(), _lowResourcesMonitor.getReasons());

        try (Socket socket0 = new Socket("localhost", _connector.getLocalPort()))
        {
            // Put the lowResourceMonitor in low mode.
            _lowResourcesMonitor.setMaxMemory(1);

            // Wait a couple of monitor periods so that
            // lowResourceMonitor detects it is in low mode.
            Thread.sleep(2 * monitorPeriod);
            assertTrue(_lowResourcesMonitor.isLowOnResources());

            // We already waited enough for lowResourceMonitor to close socket0.
            assertEquals(-1, socket0.getInputStream().read());

            // New connections are not affected by the
            // low mode until maxLowResourcesTime elapses.
            try (Socket socket1 = new Socket("localhost", _connector.getLocalPort()))
            {
                // Set a very short read timeout so we can test if the server closed.
                socket1.setSoTimeout(1);
                InputStream input1 = socket1.getInputStream();

                assertTrue(_lowResourcesMonitor.isLowOnResources());
                assertThrows(SocketTimeoutException.class, () -> input1.read());

                // Wait a couple of lowResources idleTimeouts.
                Thread.sleep(2 * lowResourcesIdleTimeout);

                // Verify the new socket is still open.
                assertTrue(_lowResourcesMonitor.isLowOnResources());
                assertThrows(SocketTimeoutException.class, () -> input1.read());

                // Let the maxLowResourcesTime elapse.
                Thread.sleep(maxLowResourcesTime);

                assertTrue(_lowResourcesMonitor.isLowOnResources());
                // Now also the new socket should be closed.
                assertEquals(-1, input1.read());
            }
        }
    }
}
