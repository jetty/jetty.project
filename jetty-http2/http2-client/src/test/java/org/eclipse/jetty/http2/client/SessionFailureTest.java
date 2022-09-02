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

package org.eclipse.jetty.http2.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SessionFailureTest extends AbstractTest
{
    @Test
    public void testWrongPreface() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onFailure(Session session, Throwable failure)
            {
                latch.countDown();
            }
        });

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            // Preface starts with byte 0x50, send something different.
            OutputStream output = socket.getOutputStream();
            output.write(0x0);
            output.flush();

            assertTrue(latch.await(5, TimeUnit.SECONDS));

            // The server will reply with a GOAWAY frame, and then shutdown.
            // Read until EOF.
            socket.setSoTimeout(1000);
            InputStream input = socket.getInputStream();
            while (true)
            {
                if (input.read() < 0)
                    break;
            }
        }
    }

    @Test
    public void testWriteFailure() throws Exception
    {
        final CountDownLatch writeLatch = new CountDownLatch(1);
        final CountDownLatch serverFailureLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                // Forcibly shutdown the output to fail the write below.
                ((HTTP2Session)stream.getSession()).getEndPoint().shutdownOutput();
                // Now try to write something: it should fail.
                stream.headers(frame, new Callback()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        writeLatch.countDown();
                    }
                });
                return null;
            }

            @Override
            public void onFailure(Session session, Throwable failure)
            {
                serverFailureLatch.countDown();
            }
        });

        final CountDownLatch clientFailureLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onFailure(Session session, Throwable failure)
            {
                clientFailureLatch.countDown();
            }
        });
        HeadersFrame frame = new HeadersFrame(newRequest("GET", HttpFields.EMPTY), null, true);
        Promise<Stream> promise = new Promise.Adapter<>();
        session.newStream(frame, promise, null);

        assertTrue(writeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientFailureLatch.await(5, TimeUnit.SECONDS));
        long start = NanoTime.now();
        while (((HTTP2Session)session).getEndPoint().isOpen())
        {
            assertThat(NanoTime.secondsElapsedFrom(start), lessThanOrEqualTo(5L));

            Thread.sleep(10);
        }
    }
}
