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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @Disabled // TODO
    public void testPid() throws Exception
    {
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        monitor.setExitVm(false);
        monitor.start();
        String key = monitor.getKey();
        int port = monitor.getPort();

        // Try more than once to be sure that the ServerSocket has not been closed.
        for (int i = 0; i < 2; ++i)
        {
            try (Socket socket = new Socket("localhost", port))
            {
                OutputStream output = socket.getOutputStream();
                String command = "pid";
                output.write((key + "\r\n" + command + "\r\n").getBytes());
                output.flush();

                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String reply = input.readLine();
                String pid = String.valueOf(ProcessHandle.current().pid());
                assertEquals(pid, reply);
                // Socket must be closed afterwards.
                assertNull(input.readLine());
            }
        }
    }

    @Test
    public void testStatus() throws Exception
    {
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        monitor.start();
        String key = monitor.getKey();
        int port = monitor.getPort();

        // Try more than once to be sure that the ServerSocket has not been closed.
        for (int i = 0; i < 2; ++i)
        {
            try (Socket socket = new Socket("localhost", port))
            {
                OutputStream output = socket.getOutputStream();
                String command = "status";
                output.write((key + "\r\n" + command + "\r\n").getBytes());
                output.flush();

                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String reply = input.readLine();
                assertEquals("OK", reply);
                // Socket must be closed afterwards.
                assertNull(input.readLine());
            }
        }
    }

    @Test
    public void testStartStopDifferentPortDifferentKey() throws Exception
    {
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        monitor.start();
        String key = monitor.getKey();
        int port = monitor.getPort();

        // try starting a 2nd time (should be ignored)
        monitor.start();

        stop("stop", port, key, true);
        awaitMonitorStop(monitor);
        assertTrue(!monitor.isAlive());

        // Should be able to change port and key because it is stopped.
        monitor.setPort(0);
        String newKey = "foo";
        monitor.setKey(newKey);
        monitor.start();

        key = monitor.getKey();
        assertEquals(newKey, key);
        port = monitor.getPort();
        assertTrue(monitor.isAlive());

        stop("stop", port, key, true);
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

            //shouldn't be registered for shutdown on jvm
            assertTrue(ShutdownThread.isRegistered(server));
            assertTrue(ShutdownMonitor.isRegistered(server));

            String key = monitor.getKey();
            int port = monitor.getPort();

            stop("stop", port, key, true);
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
        // Test setting exit true
        System.setProperty("STOP.EXIT", "true");
        ShutdownMonitor.reset();

        // The testcase
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        assertFalse(monitor.isExitVm());
    }

    @Disabled
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

            //shouldn't be registered for shutdown on jvm
            assertTrue(!ShutdownThread.isRegistered(server));
            assertTrue(ShutdownMonitor.isRegistered(server));

            String key = monitor.getKey();
            int port = monitor.getPort();

            stop("forcestop", port, key, true);
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

            stop("stop", port, key, true);
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

    public void stop(String command, int port, String key, boolean check) throws Exception
    {
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port))
        {
            // send sendCommand command
            try (OutputStream out = s.getOutputStream())
            {
                out.write((key + "\r\n" + command + "\r\n").getBytes());
                out.flush();

                if (check)
                {
                    // check for sendCommand confirmation
                    try (InputStream in = s.getInputStream();
                         InputStreamReader inReader = new InputStreamReader(in, StandardCharsets.UTF_8);
                         BufferedReader reader = new BufferedReader(inReader))
                    {
                        String response = reader.readLine();
                        assertNotNull(response, "No stopped confirmation");
                        assertEquals("Stopped", response);
                    }
                }
            }
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
