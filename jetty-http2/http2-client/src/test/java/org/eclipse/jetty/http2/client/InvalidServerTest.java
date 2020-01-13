//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InvalidServerTest extends AbstractTest
{
    @Test
    public void testInvalidPreface() throws Exception
    {
        try (ServerSocket server = new ServerSocket(0))
        {
            prepareClient();
            client.start();

            CountDownLatch failureLatch = new CountDownLatch(1);
            Promise.Completable<Session> promise = new Promise.Completable<>();
            InetSocketAddress address = new InetSocketAddress("localhost", server.getLocalPort());
            client.connect(address, new Session.Listener.Adapter()
            {
                @Override
                public void onFailure(Session session, Throwable failure)
                {
                    failureLatch.countDown();
                }
            }, promise);

            try (Socket socket = server.accept())
            {
                OutputStream output = socket.getOutputStream();
                output.write("enough_junk_bytes".getBytes(StandardCharsets.UTF_8));

                Session session = promise.get(5, TimeUnit.SECONDS);
                assertNotNull(session);

                assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

                // Verify that the client closed the socket.
                InputStream input = socket.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }
            }
        }
    }
}
