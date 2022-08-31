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

package org.eclipse.jetty.http3.tests;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.server.AbstractHTTP3ServerConnectionFactory;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreamIdleTimeoutTest extends AbstractClientServerTest
{
    @Test
    public void testClientStreamIdleTimeout() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session session)
            {
                serverSessionRef.set(session);
            }

            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if ("/idle".equals(request.getURI().getPath()))
                {
                    assertFalse(frame.isLast());
                    stream.demand();
                    return new Stream.Server.Listener()
                    {
                        @Override
                        public void onFailure(Stream.Server stream, long error, Throwable failure)
                        {
                            serverLatch.countDown();
                        }
                    };
                }
                else
                {
                    MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
                    stream.respond(new HeadersFrame(response, true));
                    return null;
                }
            }
        });

        long streamIdleTimeout = 1000;
        http3Client.getHTTP3Configuration().setStreamIdleTimeout(streamIdleTimeout);

        Session.Client clientSession = newSession(new Session.Client.Listener() {});

        CountDownLatch clientIdleLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/idle"), false), new Stream.Client.Listener()
        {
            @Override
            public boolean onIdleTimeout(Stream.Client stream, Throwable failure)
            {
                clientIdleLatch.countDown();
                // Signal to close the stream.
                return true;
            }
        }).get(5, TimeUnit.SECONDS);

        // The server does not reply, the client must idle timeout.
        assertTrue(clientIdleLatch.await(2 * streamIdleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(() -> clientSession.getStreams().isEmpty());
        await().atMost(1, TimeUnit.SECONDS).until(() -> serverSessionRef.get().getStreams().isEmpty());

        // The session should still be open, verify by sending another request.
        CountDownLatch clientLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                clientLatch.countDown();
            }
        });

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(() -> clientSession.getStreams().isEmpty());
        await().atMost(1, TimeUnit.SECONDS).until(() -> serverSessionRef.get().getStreams().isEmpty());
    }

    @Test
    public void testServerStreamIdleTimeout() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        long idleTimeout = 1000;
        CountDownLatch serverIdleLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session session)
            {
                serverSessionRef.set(session);
            }

            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if ("/idle".equals(request.getURI().getPath()))
                {
                    return new Stream.Server.Listener()
                    {
                        @Override
                        public boolean onIdleTimeout(Stream.Server stream, Throwable failure)
                        {
                            serverIdleLatch.countDown();
                            return true;
                        }
                    };
                }
                else
                {
                    MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
                    stream.respond(new HeadersFrame(response, true));
                    return null;
                }
            }
        });
        AbstractHTTP3ServerConnectionFactory h3 = connector.getConnectionFactory(AbstractHTTP3ServerConnectionFactory.class);
        assertNotNull(h3);
        h3.getHTTP3Configuration().setStreamIdleTimeout(idleTimeout);

        Session.Client clientSession = http3Client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Client.Listener() {})
            .get(5, TimeUnit.SECONDS);

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/idle"), false), new Stream.Client.Listener()
        {
            @Override
            public void onFailure(Stream.Client stream, long error, Throwable failure)
            {
                // The server idle times out, but did not send any data back.
                // However, the stream is readable and the implementation
                // reading it will cause an exception that is notified here.
                clientFailureLatch.countDown();
            }
        });

        assertTrue(serverIdleLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(clientFailureLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(() -> clientSession.getStreams().isEmpty());
        await().atMost(1, TimeUnit.SECONDS).until(() -> serverSessionRef.get().getStreams().isEmpty());

        // The session should still be open, verify by sending another request.
        CountDownLatch clientLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                clientLatch.countDown();
            }
        });

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(() -> clientSession.getStreams().isEmpty());
        await().atMost(1, TimeUnit.SECONDS).until(() -> serverSessionRef.get().getStreams().isEmpty());
    }
}
