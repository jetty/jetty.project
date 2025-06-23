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

package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.awaitility.Awaitility;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
@SuppressWarnings("removal")
public class ShutdownMonitorTest
{
    @BeforeEach
    public void initStopProperties()
    {
        System.setProperty("STOP.HOST", "");
        System.setProperty("STOP.PORT", "");
        System.setProperty("STOP.KEY", "");
        System.setProperty("STOP.EXIT", "false");
        ShutdownMonitor.reset();
    }

    @Test
    public void testPid() throws Exception
    {
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        monitor.setExitVm(false);
        monitor.start();

        String reply = sendCommand(monitor, "pid");
        String pid = String.valueOf(ProcessHandle.current().pid());
        assertEquals(pid, reply);
    }

    @Test
    public void testStatus() throws Exception
    {
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        monitor.start();

        String reply = sendCommand(monitor, "status");
        assertEquals("OK", reply);
    }

    @Test
    public void testStartStopDifferentPortDifferentKey() throws Exception
    {
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        monitor.start();

        // try starting a 2nd time (should be ignored)
        monitor.start();

        String reply = sendCommand(monitor, "stop");
        assertEquals("Stopped", reply);

        awaitMonitorStop(monitor);
        assertTrue(!monitor.isAlive());

        // Should be able to change port and key because it is stopped.
        monitor.setPort(0);
        String newKey = "foo";
        monitor.setKey(newKey);
        monitor.start();

        assertEquals(newKey, monitor.getKey());
        assertTrue(monitor.isAlive());

        reply = sendCommand(monitor, "stop");
        assertEquals("Stopped", reply);
        awaitMonitorStop(monitor);
        assertTrue(!monitor.isAlive());
    }

    /*
     * Disable these config tests because ShutdownMonitor is a 
     * static singleton that cannot be unset, and thus would
     * need each of these methods executed it its own jvm -
     * current surefire settings only fork for a single test 
     * class.
     * 
     * Undisable to test individually as needed.
     */
    @Disabled
    @Test
    public void testNoExitSystemProperty() throws Exception
    {
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        assertFalse(monitor.isExitVm());
        monitor.start();

        try (CloseableServer server = new CloseableServer())
        {
            server.setStopAtShutdown(true);
            server.start();

            // shouldn't be registered for shutdown on jvm
            assertTrue(ShutdownThread.isRegistered(server));
            assertTrue(ShutdownMonitor.isRegistered(server));

            String reply = sendCommand(monitor, "stop");
            assertEquals("Stopped", reply);
            awaitMonitorStop(monitor);

            assertTrue(!monitor.isAlive());
            assertTrue(server.stopped);
            assertTrue(!server.destroyed);
            assertTrue(!ShutdownThread.isRegistered(server));
            assertTrue(!ShutdownMonitor.isRegistered(server));
        }
    }

    @Disabled
    @Test
    public void testExitVmDefault() throws Exception
    {
        // Test setting exit default value
        System.setProperty("STOP.EXIT", "");
        ShutdownMonitor.reset();

        //Test that the default is to exit
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        assertTrue(monitor.isExitVm());
    }

    @Disabled
    @Test
    public void testExitVmTrue() throws Exception
    {
        // Test setting exit true
        System.setProperty("STOP.EXIT", "true");
        ShutdownMonitor.reset();

        // The testcase
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        assertTrue(monitor.isExitVm());
    }

    @Disabled
    @Test
    public void testExitVmFalse() throws Exception
    {
        // Test setting exit false
        System.setProperty("STOP.EXIT", "false");
        ShutdownMonitor.reset();

        // The testcase
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        assertFalse(monitor.isExitVm());
    }

    @Test
    public void testForceStopCommand() throws Exception
    {
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        assertFalse(monitor.isExitVm());
        monitor.start();

        try (CloseableServer server = new CloseableServer())
        {
            server.start();

            // shouldn't be registered for shutdown on jvm
            assertTrue(!ShutdownThread.isRegistered(server));
            assertTrue(ShutdownMonitor.isRegistered(server));

            String reply = sendCommand(monitor, "forcestop");
            assertEquals("Stopped", reply);
            awaitMonitorStop(monitor);

            assertTrue(!monitor.isAlive());
            assertTrue(server.stopped);
            assertTrue(!server.destroyed);
            assertTrue(!ShutdownThread.isRegistered(server));
            assertTrue(!ShutdownMonitor.isRegistered(server));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testOldStopCommand(boolean stopAtShutdown) throws Exception
    {
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        assertFalse(monitor.isExitVm());
        monitor.start();

        try (CloseableServer server = new CloseableServer())
        {
            server.setStopAtShutdown(stopAtShutdown);
            server.start();

            assertThat(ShutdownThread.isRegistered(server), is(stopAtShutdown));
            assertTrue(ShutdownMonitor.isRegistered(server));

            String key = monitor.getKey();
            int port = monitor.getPort();

            String reply = sendCommand(monitor, "stop");
            assertEquals("Stopped", reply);
            awaitMonitorStop(monitor);

            assertTrue(!monitor.isAlive());
            assertTrue(server.stopped);
            assertTrue(!server.destroyed);
            assertTrue(!ShutdownThread.isRegistered(server));
            assertTrue(!ShutdownMonitor.isRegistered(server));
        }
    }

    private void awaitMonitorStop(ShutdownMonitor monitor)
    {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !monitor.isListening());
    }

    public String sendCommand(ShutdownMonitor shutdownMonitor, String command) throws Exception
    {
        try (Socket s = new Socket(InetAddress.getByName(shutdownMonitor.getHost()), shutdownMonitor.getLocalPort());
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream();
             InputStreamReader inReader = new InputStreamReader(in, StandardCharsets.US_ASCII);
             BufferedReader reader = new BufferedReader(inReader))
        {
            out.write((shutdownMonitor.getKey() + "\r\n" + command + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return reader.readLine();
        }
    }

    public class CloseableServer extends Server implements Closeable
    {
        boolean destroyed = false;
        boolean stopped = false;

        @Override
        protected void doStop() throws Exception
        {
            stopped = true;
            super.doStop();
        }

        @Override
        public void destroy()
        {
            destroyed = true;
            super.destroy();
        }

        @Override
        protected void doStart() throws Exception
        {
            stopped = false;
            destroyed = false;
            super.doStart();
        }

        @Override
        public void close()
        {
            try
            {
                stop();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }
}
