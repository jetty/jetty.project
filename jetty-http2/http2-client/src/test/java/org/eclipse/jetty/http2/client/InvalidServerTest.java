//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import org.junit.Assert;
import org.junit.Test;

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
                Assert.assertNotNull(session);

                Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

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
