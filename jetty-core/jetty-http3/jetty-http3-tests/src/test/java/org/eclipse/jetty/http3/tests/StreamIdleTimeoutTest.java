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

package org.eclipse.jetty.http3.tests;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.server.AbstractHTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.internal.HTTP3StreamServer;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreamIdleTimeoutTest extends AbstractClientServerTest
{
    private StacklessLogging sll;

    @BeforeEach
    public void setUp()
    {
        sll = new StacklessLogging(HTTP3StreamServer.class);
    }

    @AfterEach
    public void tearDown()
    {
        sll.close();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientStreamIdleTimeout(TransportType transportType) throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session.Server session)
            {
                serverSessionRef.set(session);
            }

            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if ("/idle".equals(request.getHttpURI().getPath()))
                {
                    assertFalse(frame.isLast());
                    return new Stream.Server.Listener()
                    {
                        @Override
                        public void onRequest(Stream.Server stream, HeadersFrame frame)
                        {
                            // Do not demand, so the failure is delivered
                            // to onFailure() rather than through read().
                        }

                        @Override
                        public void onFailure(Stream.Server stream, long error, Throwable failure)
                        {
                            serverLatch.countDown();
                        }
                    };
                }
                else
                {
                    return new Stream.Server.Listener()
                    {
                        @Override
                        public void onRequest(Stream.Server stream, HeadersFrame frame)
                        {
                            MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY);
                            stream.respond(new HeadersFrame(response, true), Promise.Invocable.noop());
                        }
                    };
                }
            }
        });

        long streamIdleTimeout = 1000;
        http3Client.getHTTP3Configuration().setStreamIdleTimeout(streamIdleTimeout);

        Session.Client clientSession = newSession(new Session.Client.Listener() {});

        CountDownLatch clientIdleLatch = new CountDownLatch(1);
        Blocker.<Stream>blockWithPromise(5, TimeUnit.SECONDS, p ->
            clientSession.newRequest(new HeadersFrame(newRequest("/idle"), false), new Stream.Client.Listener()
            {
                @Override
                public void onIdleTimeout(Stream.Client stream, Throwable failure, Promise<Boolean> promise)
                {
                    clientIdleLatch.countDown();
                    // Signal to close the stream.
                    promise.succeeded(true);
                }
            }, p));

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
        }, Promise.Invocable.noop());

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(() -> clientSession.getStreams().isEmpty());
        await().atMost(1, TimeUnit.SECONDS).until(() -> serverSessionRef.get().getStreams().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testServerStreamIdleTimeout(TransportType transportType) throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        long idleTimeout = 1000;
        CountDownLatch serverIdleLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session.Server session)
            {
                serverSessionRef.set(session);
            }

            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if ("/idle".equals(request.getHttpURI().getPath()))
                {
                    return new Stream.Server.Listener()
                    {
                        @Override
                        public void onIdleTimeout(Stream.Server stream, TimeoutException failure, Promise<Boolean> promise)
                        {
                            serverIdleLatch.countDown();
                            promise.succeeded(true);
                        }
                    };
                }
                else
                {
                    return new Stream.Server.Listener()
                    {
                        @Override
                        public void onRequest(Stream.Server stream, HeadersFrame frame)
                        {
                            MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY);
                            stream.respond(new HeadersFrame(response, true), Promise.Invocable.noop());
                        }
                    };
                }
            }
        });
        AbstractHTTP3ServerConnectionFactory h3 = connector.getConnectionFactory(AbstractHTTP3ServerConnectionFactory.class);
        assertNotNull(h3);
        h3.getHTTP3Configuration().setStreamIdleTimeout(idleTimeout);

        Session.Client clientSession = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> http3Client.connect(transport, new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Client.Listener() {}, p));

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
        }, Promise.Invocable.noop());

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
        }, Promise.Invocable.noop());

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(() -> clientSession.getStreams().isEmpty());
        await().atMost(1, TimeUnit.SECONDS).until(() -> serverSessionRef.get().getStreams().isEmpty());
    }
}
