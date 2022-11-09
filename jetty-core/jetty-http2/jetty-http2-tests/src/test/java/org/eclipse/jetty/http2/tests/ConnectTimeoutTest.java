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

package org.eclipse.jetty.http2.tests;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectTimeoutTest extends AbstractTest
{
    @Test
    @Tag("external")
    public void testConnectTimeout() throws Exception
    {
        // Using IANA hosted example.com:81 to reliably produce a Connect Timeout.
        final String host = "example.com";
        final int port = 81;
        int connectTimeout = 1000;
        assumeConnectTimeout(host, port, connectTimeout);

        start(new ServerSessionListener() {});
        http2Client.setConnectTimeout(connectTimeout);

        InetSocketAddress address = new InetSocketAddress(host, port);
        final CountDownLatch latch = new CountDownLatch(1);
        http2Client.connect(address, new Session.Listener() {}, new Promise.Adapter<>()
        {
            @Override
            public void failed(Throwable x)
            {
                latch.countDown();
            }
        });

        assertTrue(latch.await(2 * connectTimeout, TimeUnit.MILLISECONDS));
    }

    private void assumeConnectTimeout(String host, int port, int connectTimeout)
    {
        boolean socketTimeout = false;

        try (Socket socket = new Socket())
        {
            // Try to connect to a private address in the 10.x.y.z range.
            // These addresses are usually not routed, so an attempt to
            // connect to them will hang the connection attempt, which is
            // what we want to simulate in this test.
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
        }
        catch (SocketTimeoutException x)
        {
            // We expect a timeout during connect, allow test to continue.
            socketTimeout = true;
        }
        catch (Throwable x)
        {
            // Dump stacktrace when there is an unexpected test failure
            // Useful when debugging
            x.printStackTrace(System.err);
        }

        // Abort the test if we can connect.
        Assumptions.assumeTrue(socketTimeout, "Should have seen connect timeout");
    }
}
