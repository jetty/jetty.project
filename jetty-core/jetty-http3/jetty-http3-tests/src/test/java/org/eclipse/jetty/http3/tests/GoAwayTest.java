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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.HTTP3Session;
import org.eclipse.jetty.http3.HTTP3Stream;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3SessionClient;
import org.eclipse.jetty.http3.client.internal.ClientHTTP3Session;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.server.internal.HTTP3SessionServer;
import org.eclipse.jetty.http3.server.internal.ServerHTTP3Session;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.quic.common.SessionContainer;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GoAwayTest extends AbstractClientServerTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testClientGoAwayServerReplies(TransportType transportType) throws Exception
    {
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        AtomicReference<HTTP3SessionServer> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                serverSessionRef.set((HTTP3SessionServer)session);
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

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        HTTP3SessionClient clientSession = (HTTP3SessionClient)newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isLast() && response.getStatus() == HttpStatus.OK_200)
                    clientSession.goAway(false, Promise.Invocable.noop());
            }
        }, Promise.Invocable.noop());

        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        HTTP3SessionServer serverSession = serverSessionRef.get();
        assertTrue(serverSession.isClosed());
        assertTrue(serverSession.getStreams().isEmpty());
        ServerHTTP3Session serverProtocolSession = (ServerHTTP3Session)serverSession.getProtocolSession();
        // While HTTP/3 is completely closed, QUIC may still be exchanging packets, so we need to await().
        await().atMost(3, TimeUnit.SECONDS).until(() -> serverProtocolSession.getStreamEndPoints().isEmpty());
        await().atMost(3, TimeUnit.SECONDS).until(() -> serverProtocolSession.getSession().getStreams().isEmpty());

        assertTrue(clientSession.isClosed());
        assertTrue(clientSession.getStreams().isEmpty());
        ClientHTTP3Session clientProtocolSession = (ClientHTTP3Session)clientSession.getProtocolSession();
        // While HTTP/3 is completely closed, QUIC may still be exchanging packets, so we need to await().
        await().atMost(3, TimeUnit.SECONDS).until(() -> clientProtocolSession.getStreamEndPoints().isEmpty());
        await().atMost(3, TimeUnit.SECONDS).until(() -> clientProtocolSession.getSession().getStreams().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testServerGoAwayWithInFlightStreamClientFailsStream(TransportType transportType) throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
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

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });

        CompletableFuture<Stream> completable = new CompletableFuture<>();
        CountDownLatch streamFailureLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/1"), true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                // Simulate the server sending a GOAWAY while the client sends a second request.
                // The server sends a lastStreamId for the first request, and discards the second.
                serverSessionRef.get().goAway(false, Promise.Invocable.noop());
                // The client sends the second request and should eventually fail it
                // locally, in two ways: either it is sent to the server, and the server
                // fails it because the request has a larger streamId than what sent with
                // the GOAWAY; or the client received the GOAWAY from the server and
                // the stream could not be created, and the request is never sent.
                clientSession.newRequest(new HeadersFrame(newRequest("/2"), true), new Stream.Client.Listener()
                {
                    @Override
                    public void onFailure(Stream.Client stream, long error, Throwable failure)
                    {
                        streamFailureLatch.countDown();
                    }
                }, Promise.Invocable.toPromise(completable));
            }
        }, Promise.Invocable.noop());

        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        // If the request was successfully sent to the server, then expect
        // the stream failure event, otherwise was not sent to the server.
        CountDownLatch latch = completable.handle((s, x) ->
        {
            if (x == null)
                return streamFailureLatch;
            return null;
        }).get(5, TimeUnit.SECONDS);
        if (latch != null)
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)clientSession).isClosed());
        assertTrue(((HTTP3Session)serverSessionRef.get()).isClosed());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testServerGracefulGoAway(TransportType transportType) throws Exception
    {
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                serverSessionRef.set(session);
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

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                    clientGracefulGoAwayLatch.countDown();
                else
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });
        CountDownLatch clientLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isLast() && response.getStatus() == HttpStatus.OK_200)
                    clientLatch.countDown();
            }
        }, Promise.Invocable.noop());

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        // Send a graceful GOAWAY from the server.
        // Because the server had no pending streams, it will send also a non-graceful GOAWAY.
        serverSessionRef.get().goAway(true, Promise.Invocable.noop());

        assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverSessionRef.get()).isClosed());
        assertTrue(((HTTP3Session)clientSession).isClosed());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testServerGracefulGoAwayWithStreamsServerClosesWhenLastStreamCloses(TransportType transportType) throws Exception
    {
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        AtomicReference<Stream.Server> serverStreamRef = new AtomicReference<>();
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                serverSessionRef.set(session);
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        serverStreamRef.set(stream);
                        // Send a graceful GOAWAY while processing a stream.
                        session.goAway(true, Promise.Invocable.noop());
                    }
                };
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                    clientGracefulGoAwayLatch.countDown();
                else
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });
        CountDownLatch clientLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isLast() && response.getStatus() == HttpStatus.OK_200)
                    clientLatch.countDown();
            }
        }, Promise.Invocable.noop());

        // Wait for the graceful GOAWAY.
        assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));

        // Now the client cannot create new streams.
        CountDownLatch failureLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), null, new Promise.Invocable.NonBlocking<>()
        {
            @Override
            public void failed(Throwable x)
            {
                failureLatch.countDown();
            }
        });
        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

        // The client must not reply to a graceful GOAWAY.
        assertFalse(serverGoAwayLatch.await(1, TimeUnit.SECONDS));

        // Previous streams must complete successfully.
        Stream.Server serverStream = serverStreamRef.get();
        MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY);
        serverStream.respond(new HeadersFrame(response, true), Promise.Invocable.noop());

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        // The server should have sent the GOAWAY after the last stream completed.

        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverSessionRef.get()).isClosed());
        assertTrue(((HTTP3Session)clientSession).isClosed());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientGoAwayWithStreamsServerClosesWhenLastStreamCloses(TransportType transportType) throws Exception
    {
        AtomicReference<Stream.Server> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        serverStreamRef.set(stream);
                        serverStreamLatch.countDown();
                    }
                };
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isLast() && response.getStatus() == HttpStatus.OK_200)
                    clientLatch.countDown();
            }
        }, Promise.Invocable.noop());

        assertTrue(serverStreamLatch.await(5, TimeUnit.SECONDS));

        // The client sends a GOAWAY.
        clientSession.goAway(false, Promise.Invocable.noop());

        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));

        // The client must not receive a GOAWAY until the all streams are completed.
        assertFalse(clientGoAwayLatch.await(1, TimeUnit.SECONDS));

        // Complete the stream.
        Stream.Server serverStream = serverStreamRef.get();
        MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY);
        serverStream.respond(new HeadersFrame(response, true), Promise.Invocable.noop());

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverStreamRef.get().getSession()).isClosed());
        assertTrue(((HTTP3Session)clientSession).isClosed());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testServerGracefulGoAwayWithStreamsClientGoAwayServerClosesWhenLastStreamCloses(TransportType transportType) throws Exception
    {
        AtomicReference<Stream.Server> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        serverStreamRef.set(stream);
                        serverStreamLatch.countDown();
                        // Send a graceful GOAWAY while processing a stream.
                        session.goAway(true, Promise.Invocable.noop());
                    }
                };
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                {
                    // Send a GOAWAY when receiving a graceful GOAWAY.
                    session.goAway(false, Promise.Invocable.noop());
                }
                else
                {
                    clientGoAwayLatch.countDown();
                }
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isLast() && response.getStatus() == HttpStatus.OK_200)
                    clientLatch.countDown();
            }
        }, Promise.Invocable.noop());

        // The server has a pending stream, so it does not send the non-graceful GOAWAY yet.
        assertFalse(clientGoAwayLatch.await(1, TimeUnit.SECONDS));

        // Complete the stream, the server should send the non-graceful GOAWAY.
        Stream.Server serverStream = serverStreamRef.get();
        MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY);
        serverStream.respond(new HeadersFrame(response, true), Promise.Invocable.noop());

        // The server already received the client GOAWAY,
        // so completing the last stream produces a close event.
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
        // The client should receive the server non-graceful GOAWAY.
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverStreamRef.get().getSession()).isClosed());
        assertTrue(((HTTP3Session)clientSession).isClosed());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientGracefulGoAwayWithStreamsServerGracefulGoAwayServerClosesWhenLastStreamCloses(TransportType transportType) throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverRequestLatch = new CountDownLatch(1);
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        stream.demand();
                        serverStreamRef.set(stream);
                        serverRequestLatch.countDown();
                    }

                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        Content.Chunk chunk = stream.read();
                        if (chunk != null)
                            chunk.release();
                        if (chunk != null && chunk.isLast())
                        {
                            MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY);
                            stream.respond(new HeadersFrame(response, true), Promise.Invocable.noop());
                        }
                        else
                        {
                            stream.demand();
                        }
                    }
                };
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                {
                    // Send a graceful GOAWAY.
                    session.goAway(true, Promise.Invocable.noop());
                }
                else
                {
                    serverGoAwayLatch.countDown();
                }
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                    clientGracefulGoAwayLatch.countDown();
                else
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });
        Stream clientStream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> clientSession.newRequest(new HeadersFrame(newRequest("/"), false), new Stream.Client.Listener() {}, p));

        assertTrue(serverRequestLatch.await(5, TimeUnit.SECONDS));

        // Send a graceful GOAWAY from the client.
        clientSession.goAway(true, Promise.Invocable.noop());

        // The server should send a graceful GOAWAY.
        assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));

        // Complete the stream.
        clientStream.data(new DataFrame(BufferUtil.EMPTY_BUFFER, true), Promise.Invocable.noop());

        // Both client and server should send a non-graceful GOAWAY.
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverStreamRef.get().getSession()).isClosed());
        assertTrue(((HTTP3Session)clientSession).isClosed());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientDisconnectServerCloses(TransportType transportType) throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch settingsLatch = new CountDownLatch(2);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                serverSessionRef.set(session);
                settingsLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });

        HTTP3SessionClient clientSession = (HTTP3SessionClient)newSession(new Session.Client.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        // Issue a network disconnection.
        clientSession.getProtocolSession().getSession().disconnect(ErrorCode.INTERNAL_ERROR.code(), "disconnect", null, Callback.NOOP);

        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverSessionRef.get()).isClosed());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testServerGracefulGoAwayClientDisconnectServerCloses(TransportType transportType) throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch settingsLatch = new CountDownLatch(2);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                serverSessionRef.set(session);
                settingsLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });

        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                // Reply to the graceful GOAWAY from the server with a network disconnection.
                ((HTTP3Session)session).getProtocolSession().getSession().disconnect(ErrorCode.INTERNAL_ERROR.code(), "disconnect", null, Callback.NOOP);
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        // Send a graceful GOAWAY to the client.
        serverSessionRef.get().goAway(true, Promise.Invocable.noop());

        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverSessionRef.get()).isClosed());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientIdleTimeout(TransportType transportType) throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<HTTP3Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session.Server session)
            {
                serverSessionRef.set((HTTP3Session)session);
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });
        http3Client.getClientConnector().setIdleTimeout(Duration.ofMillis(idleTimeout));

        CountDownLatch clientIdleTimeoutLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        HTTP3Session clientSession = (HTTP3Session)newSession(new Session.Client.Listener()
        {
            @Override
            public boolean onIdleTimeout(Session session)
            {
                clientIdleTimeoutLatch.countDown();
                return true;
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });

        assertTrue(clientIdleTimeoutLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // Client should send a GOAWAY to the server, which should reply.
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        HTTP3Session serverSession = serverSessionRef.get();
        assertTrue(serverSession.isClosed());
        assertTrue(clientSession.isClosed());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testServerIdleTimeout(TransportType transportType) throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverIdleTimeoutLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session.Server session)
            {
                serverSessionRef.set(session);
            }

            @Override
            public boolean onIdleTimeout(Session session)
            {
                serverIdleTimeoutLatch.countDown();
                return true;
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });
        connector.setIdleTimeout(idleTimeout);

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (!frame.isGraceful())
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });

        assertTrue(serverIdleTimeoutLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // Server should send a GOAWAY to the client.
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        // The client replied to server's GOAWAY, but the server already closed.
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverSessionRef.get()).isClosed());
        assertTrue(((HTTP3Session)clientSession).isClosed());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testServerGracefulGoAwayWithStreamsServerIdleTimeout(TransportType transportType) throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
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
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        // Send a graceful GOAWAY.
                        session.goAway(true, Promise.Invocable.noop());
                    }
                };
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });
        connector.setIdleTimeout(idleTimeout);

        CountDownLatch clientGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        HTTP3SessionClient clientSession = (HTTP3SessionClient)newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                    clientGracefulGoAwayLatch.countDown();
                else
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });
        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        // Send request headers but not data.
        clientSession.newRequest(new HeadersFrame(newRequest("/"), false), new Stream.Client.Listener()
        {
            @Override
            public void onFailure(Stream.Client stream, long error, Throwable failure)
            {
                clientFailureLatch.countDown();
            }
        }, Promise.Invocable.noop());

        assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));
        // Server idle timeout sends a non-graceful GOAWAY.
        assertTrue(clientFailureLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        HTTP3SessionServer serverSession = (HTTP3SessionServer)serverSessionRef.get();
        assertTrue(serverSession.isClosed());
        assertTrue(serverSession.getStreams().isEmpty());
        ServerHTTP3Session serverProtocolSession = (ServerHTTP3Session)serverSession.getProtocolSession();
        // While HTTP/3 is completely closed, QUIC may still be exchanging packets, so we need to await().
        await().atMost(1, TimeUnit.SECONDS).until(() -> serverProtocolSession.getStreamEndPoints().isEmpty());
        await().atMost(1, TimeUnit.SECONDS).until(() -> serverProtocolSession.getSession().getStreams().isEmpty());

        assertTrue(clientSession.isClosed());
        assertTrue(clientSession.getStreams().isEmpty());
        ClientHTTP3Session clientProtocolSession = (ClientHTTP3Session)clientSession.getProtocolSession();
        // While HTTP/3 is completely closed, QUIC may still be exchanging packets, so we need to await().
        await().atMost(1, TimeUnit.SECONDS).until(() -> clientProtocolSession.getStreamEndPoints().isEmpty());
        await().atMost(3, TimeUnit.SECONDS).until(() -> clientProtocolSession.getSession().getStreams().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientGracefulGoAwayWithStreamsServerIdleTimeout(TransportType transportType) throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverRequestLatch = new CountDownLatch(1);
        CountDownLatch serverGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
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
                serverRequestLatch.countDown();
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                    serverGracefulGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });
        connector.setIdleTimeout(idleTimeout);

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        HTTP3SessionClient clientSession = (HTTP3SessionClient)newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });
        CountDownLatch streamFailureLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), false), new Stream.Client.Listener()
        {
            @Override
            public void onFailure(Stream.Client stream, long error, Throwable failure)
            {
                streamFailureLatch.countDown();
            }
        }, Promise.Invocable.noop());

        assertTrue(serverRequestLatch.await(5, TimeUnit.SECONDS));

        // Client sends a graceful GOAWAY.
        clientSession.goAway(true, Promise.Invocable.noop());

        assertTrue(serverGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(streamFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverSessionRef.get()).isClosed());
        assertTrue(clientSession.isClosed());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testServerGoAwayWithStreamsThenDisconnect(TransportType transportType) throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                serverSessionRef.set(session);
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        // Don't reply, don't reset the stream, just send the GOAWAY.
                        session.goAway(false, Promise.Invocable.noop());
                    }
                };
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        HTTP3SessionClient clientSession = (HTTP3SessionClient)newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), false), new Stream.Client.Listener()
        {
            @Override
            public void onFailure(Stream.Client stream, long error, Throwable failure)
            {
                clientFailureLatch.countDown();
            }
        }, Promise.Invocable.noop());

        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));

        // Neither the client nor the server are finishing
        // the pending stream, so force the disconnect on the server.
        HTTP3Session serverSession = (HTTP3Session)serverSessionRef.get();
        serverSession.getProtocolSession().getSession().disconnect(ErrorCode.INTERNAL_ERROR.code(), "disconnect", null, Callback.NOOP);

        assertTrue(clientFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(clientSession.isClosed());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientStop(TransportType transportType) throws Exception
    {
        CountDownLatch settingsLatch = new CountDownLatch(2);
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        newSession(new Session.Client.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        // Wait a bit more to allow the unidirectional streams to be setup.
        Thread.sleep(1000);

        // Stopping the HttpClient will also stop the HTTP3Client.
        httpClient.stop();

        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testServerStop(TransportType transportType) throws Exception
    {
        CountDownLatch settingsLatch = new CountDownLatch(2);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                serverDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        newSession(new Session.Client.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });

        assertTrue(settingsLatch.await(10, TimeUnit.SECONDS));

        // Wait a bit more to allow the unidirectional streams to be setup.
        Thread.sleep(1000);

        server.stop();

        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientShutdown(TransportType transportType) throws Exception
    {
        AtomicReference<HTTP3Stream> serverStreamRef = new AtomicReference<>();
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        serverStreamRef.set((HTTP3Stream)stream);
                        stream.respond(new HeadersFrame(new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY), false), Promise.Invocable.noop());
                    }
                };
            }
        });

        CountDownLatch responseLatch = new CountDownLatch(1);
        CountDownLatch dataLatch = new CountDownLatch(1);
        HTTP3SessionClient clientSession = (HTTP3SessionClient)newSession(new Session.Client.Listener() {});
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                responseLatch.countDown();
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream.Client stream)
            {
                Content.Chunk chunk = stream.read();
                if (chunk != null)
                {
                    chunk.release();
                    if (chunk.isLast())
                    {
                        dataLatch.countDown();
                        return;
                    }
                }
                stream.demand();
            }
        }, Promise.Invocable.noop());

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> shutdown = http3Client.shutdown();

        // Shutdown must not complete yet.
        assertThrows(TimeoutException.class, () -> shutdown.get(1, TimeUnit.SECONDS));

        // Complete the response.
        serverStreamRef.get().data(new DataFrame(BufferUtil.EMPTY_BUFFER, true), Promise.Invocable.noop());

        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
        shutdown.get(5, TimeUnit.SECONDS);

        await().atMost(5, TimeUnit.SECONDS).until(http3Client::isStopped);

        SessionContainer container = http3Client.getBean(SessionContainer.class);
        assertNotNull(container);
        await().atMost(5, TimeUnit.SECONDS).until(container::isEmpty);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testServerShutdown(TransportType transportType) throws Exception
    {
        AtomicReference<HTTP3Stream> serverStreamRef = new AtomicReference<>();
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        serverStreamRef.set((HTTP3Stream)stream);
                        stream.respond(new HeadersFrame(new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY), false), Promise.Invocable.noop());
                    }
                };
            }
        });

        CountDownLatch responseLatch = new CountDownLatch(1);
        CountDownLatch dataLatch = new CountDownLatch(1);
        HTTP3SessionClient clientSession = (HTTP3SessionClient)newSession(new Session.Client.Listener() {});
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                responseLatch.countDown();
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream.Client stream)
            {
                Content.Chunk chunk = stream.read();
                if (chunk != null)
                {
                    chunk.release();
                    if (chunk.isLast())
                    {
                        dataLatch.countDown();
                        return;
                    }
                }
                stream.demand();
            }
        }, Promise.Invocable.noop());

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> shutdown = connector.shutdown();
        // Shutdown must not complete yet.
        assertThrows(TimeoutException.class, () -> shutdown.get(1, TimeUnit.SECONDS));

        // Complete the response.
        serverStreamRef.get().data(new DataFrame(BufferUtil.EMPTY_BUFFER, true), Promise.Invocable.noop());

        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
        shutdown.get(5, TimeUnit.SECONDS);

        await().atMost(5, TimeUnit.SECONDS).until(() -> connector.isStopped());

        SessionContainer container = connector.getBean(SessionContainer.class);
        assertNotNull(container);
        await().atMost(5, TimeUnit.SECONDS).until(container::isEmpty);
    }
}
