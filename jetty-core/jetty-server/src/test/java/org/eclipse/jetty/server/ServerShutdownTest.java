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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerShutdownTest
{
    @Test
    public void testPid() throws Exception
    {
        ServerShutdown shutdown = new ServerShutdown("localhost", 0, null, false);
        shutdown.start();

        String reply = sendCommand(shutdown, "pid");
        String pid = String.valueOf(ProcessHandle.current().pid());
        assertEquals(pid, reply);
    }

    @Test
    public void testStatus() throws Exception
    {
        ServerShutdown shutdown = new ServerShutdown("localhost", 0, null, false);
        shutdown.start();

        String reply = sendCommand(shutdown, "status");
        assertEquals("OK", reply);
    }

    @Test
    public void testStartStopDifferentPortDifferentKey() throws Exception
    {
        ServerShutdown shutdown = new ServerShutdown("localhost", 0, null, false);
        shutdown.start();

        // try starting a 2nd time (should be ignored)
        shutdown.start();

        sendCommand(shutdown, "stop");
        awaitShutdownListening(shutdown);
        assertFalse(shutdown.isListening());

        // Should be able to change port and key because it is stopped.
        shutdown.setPort(0);
        String newKey = "foo";
        shutdown.setKey(newKey);
        shutdown.start();

        String key = shutdown.getKey();
        assertEquals(newKey, key);
        assertTrue(shutdown.isListening());

        sendCommand(shutdown, "stop");
        awaitShutdownListening(shutdown);
        assertFalse(shutdown.isListening());
    }

    @Test
    public void testNoExit() throws Exception
    {
        ServerShutdown shutdown = new ServerShutdown("localhost", 0, null, false);
        assertFalse(shutdown.isExitVm());

        try (CloseableServer server = new CloseableServer())
        {
            server.addBean(shutdown);
            server.setStopAtShutdown(true);
            server.start();

            //shouldn't be registered for shutdown on jvm
            assertTrue(ShutdownThread.isRegistered(server));
            assertTrue(shutdown.hasComponent(server));

            sendCommand(shutdown, "stop");
            awaitShutdownListening(shutdown);

            assertFalse(shutdown.isListening());
            assertTrue(server.stopped);
            assertFalse(server.destroyed);
            assertFalse(ShutdownThread.isRegistered(server));
            assertFalse(shutdown.hasComponent(server));
        }
    }

    @Test
    public void testForceStopCommand() throws Exception
    {
        ServerShutdown shutdown = new ServerShutdown("localhost", 0, null, false);

        try (CloseableServer server = new CloseableServer())
        {
            server.addBean(shutdown);
            server.start();

            //shouldn't be registered for shutdown on jvm
            assertFalse(ShutdownThread.isRegistered(server));
            assertTrue(shutdown.hasComponent(server));

            sendCommand(shutdown, "forcestop");
            awaitShutdownListening(shutdown);

            assertFalse(shutdown.isListening());
            assertTrue(server.stopped);
            assertFalse(server.destroyed);
            assertFalse(ShutdownThread.isRegistered(server));
            assertFalse(shutdown.hasComponent(server));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testOldStopCommand(boolean stopAtShutdown) throws Exception
    {
        ServerShutdown shutdown = new ServerShutdown("localhost", 0, null, false);

        try (CloseableServer server = new CloseableServer())
        {
            server.addBean(shutdown);
            server.setStopAtShutdown(stopAtShutdown);
            server.start();

            assertThat(ShutdownThread.isRegistered(server), is(stopAtShutdown));
            assertTrue(shutdown.hasComponent(server));

            sendCommand(shutdown, "stop");
            awaitShutdownListening(shutdown);

            assertFalse(shutdown.isListening());
            assertTrue(server.stopped);
            assertFalse(server.destroyed);
            assertFalse(ShutdownThread.isRegistered(server));
            assertFalse(shutdown.hasComponent(server));
        }
    }

    private void awaitShutdownListening(ServerShutdown shutdown)
    {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !shutdown.isListening());
    }

    public String sendCommand(ServerShutdown serverShutdown, String command) throws Exception
    {
        try (Socket s = new Socket(InetAddress.getByName(serverShutdown.getHost()), serverShutdown.getLocalPort());
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream();
             InputStreamReader inReader = new InputStreamReader(in, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(inReader))
        {
            out.write((serverShutdown.getKey() + "\r\n" + command + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return reader.readLine();
        }
    }

    public static class CloseableServer extends Server implements Closeable
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
