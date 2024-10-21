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
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;

import org.eclipse.jetty.util.thread.ShutdownThread;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ShutdownMonitorTest
{
    /**
     * Throw away the current ShutdownMonitor singleton and
     * create a new one. Note that this will read the System
     * properties in the constructor, thus you cannot set these
     * System properties inside any of these tests and expect
     * them to take effect.
     */
    @AfterEach
    public void dispose()
    {
        ShutdownMonitor.reset();
    }
    
    @Test
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
        // monitor.setDebug(true);
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
        // monitor.setDebug(true);
        monitor.setPort(0);
        monitor.setExitVm(false);
        monitor.start();
        String key = monitor.getKey();
        int port = monitor.getPort();

        // try starting a 2nd time (should be ignored)
        monitor.start();

        stop("stop", port, key, true);
        monitor.await();
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
        monitor.await();
        assertTrue(!monitor.isAlive());
    }

    /**
     * This test can only be run if the System property
     * STOP.EXIT has been set. This can either be done in
     * the IDE, or via the surefire plugin in the pom.
     * This test expects STOP.EXIT to be FALSE.
     */
    @Test
    @EnabledIfSystemProperty(named="STOP.EXIT", matches="[Tt][Rr][Uu][Ee]|[Ff][Aa][Ll][Ss][Ee]")
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
            monitor.await();

            assertTrue(!monitor.isAlive());
            assertTrue(server.stopped);
            assertTrue(!server.destroyed);
            assertTrue(!ShutdownThread.isRegistered(server));
            assertTrue(!ShutdownMonitor.isRegistered(server));
        }
    }

    @Test
    @DisabledIf("isStopExitSystemPropertySet")
    public void testExitVmDefault() throws Exception
    {
        //If the STOP.EXIT system property is set, then this will
        //overwrite the default, so this test would be meaningless
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        assertTrue(monitor.isExitVm());
    }

    @Test
    public void testExitVmTrue() throws Exception
    {
        //Note that this cannot be tested via the System property STOP.EXIT=true
        //because it would have to be set for the whole jvm (eg via surefire plugin config)
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        monitor.setExitVm(true);
        assertTrue(monitor.isExitVm());
    }

    @Test
    public void testForceStopCommand() throws Exception
    {
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        // monitor.setDebug(true);
        monitor.setPort(0);
        monitor.setExitVm(false);
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
            monitor.await();

            assertTrue(!monitor.isAlive());
            assertTrue(server.stopped);
            assertTrue(!server.destroyed);
            assertTrue(!ShutdownThread.isRegistered(server));
            assertTrue(!ShutdownMonitor.isRegistered(server));
        }
    }

    @Test
    public void testOldStopCommandWithStopOnShutdownTrue() throws Exception
    {
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        monitor.setPort(0);
        monitor.setExitVm(false);
        monitor.start();

        try (CloseableServer server = new CloseableServer())
        {
            server.setStopAtShutdown(true);
            server.start();

            //should be registered for shutdown on exit
            assertTrue(ShutdownThread.isRegistered(server));
            assertTrue(ShutdownMonitor.isRegistered(server));

            String key = monitor.getKey();
            int port = monitor.getPort();

            stop("stop", port, key, true);
            monitor.await();

            assertTrue(!monitor.isAlive());
            assertTrue(server.stopped);
            assertTrue(!server.destroyed);
            assertTrue(!ShutdownThread.isRegistered(server));
            assertTrue(!ShutdownMonitor.isRegistered(server));
        }
    }

    @Test
    public void testOldStopCommandWithStopOnShutdownFalse() throws Exception
    {
        ShutdownMonitor monitor = ShutdownMonitor.getInstance();
        // monitor.setDebug(true);
        monitor.setPort(0);
        monitor.setExitVm(false);
        monitor.start();

        try (CloseableServer server = new CloseableServer())
        {
            server.setStopAtShutdown(false);
            server.start();

            assertTrue(!ShutdownThread.isRegistered(server));
            assertTrue(ShutdownMonitor.isRegistered(server));

            String key = monitor.getKey();
            int port = monitor.getPort();

            stop("stop", port, key, true);
            monitor.await();

            assertTrue(!monitor.isAlive());
            assertTrue(!server.stopped);
            assertTrue(!server.destroyed);
            assertTrue(!ShutdownThread.isRegistered(server));
            assertTrue(ShutdownMonitor.isRegistered(server));
        }
    }

    public void stop(String command, int port, String key, boolean check) throws Exception
    {
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port))
        {
            // send stop command
            try (OutputStream out = s.getOutputStream())
            {
                out.write((key + "\r\n" + command + "\r\n").getBytes());
                out.flush();

                if (check)
                {
                    // check for stop confirmation
                    LineNumberReader lin = new LineNumberReader(new InputStreamReader(s.getInputStream()));
                    String response;
                    if ((response = lin.readLine()) != null)
                        assertEquals("Stopped", response);
                    else
                        throw new IllegalStateException("No stop confirmation");
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
