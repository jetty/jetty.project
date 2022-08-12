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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.internal.HTTP3SessionClient;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.http3.server.internal.HTTP3SessionServer;
import org.eclipse.jetty.quic.client.ClientQuicSession;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.server.ServerQuicSession;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GoAwayTest extends AbstractClientServerTest
{
    @Test
    public void testClientGoAwayServerReplies() throws Exception
    {
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        AtomicReference<HTTP3SessionServer> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverSessionRef.set((HTTP3SessionServer)stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
                stream.respond(new HeadersFrame(response, true));
                return null;
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
                    clientSession.goAway(false);
            }
        });

        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        HTTP3SessionServer serverSession = serverSessionRef.get();
        assertTrue(serverSession.isClosed());
        assertTrue(serverSession.getStreams().isEmpty());
        ServerQuicSession serverQuicSession = serverSession.getProtocolSession().getQuicSession();
        // While HTTP/3 is completely closed, QUIC may still be exchanging packets, so we need to await().
        await().atMost(1, TimeUnit.SECONDS).until(() -> serverQuicSession.getQuicStreamEndPoints().isEmpty());
        await().atMost(1, TimeUnit.SECONDS).until(() -> serverQuicSession.getQuicConnection().getQuicSessions().isEmpty());

        assertTrue(clientSession.isClosed());
        assertTrue(clientSession.getStreams().isEmpty());
        ClientQuicSession clientQuicSession = clientSession.getProtocolSession().getQuicSession();
        // While HTTP/3 is completely closed, QUIC may still be exchanging packets, so we need to await().
        await().atMost(1, TimeUnit.SECONDS).until(() -> clientQuicSession.getQuicStreamEndPoints().isEmpty());
        QuicConnection quicConnection = clientQuicSession.getQuicConnection();
        await().atMost(1, TimeUnit.SECONDS).until(() -> quicConnection.getQuicSessions().isEmpty());
        await().atMost(1, TimeUnit.SECONDS).until(() -> quicConnection.getEndPoint().isOpen(), is(false));
    }

    @Test
    public void testServerGoAwayWithInFlightStreamClientFailsStream() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverSessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
                stream.respond(new HeadersFrame(response, true));
                return null;
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

        CountDownLatch streamFailureLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/1"), true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                // Simulate the server sending a GOAWAY while the client sends a second request.
                // The server sends a lastStreamId for the first request, and discards the second.
                serverSessionRef.get().goAway(false);
                // The client sends the second request and should eventually fail it
                // locally since it has a larger streamId, and the server discarded it.
                clientSession.newRequest(new HeadersFrame(newRequest("/2"), true), new Stream.Client.Listener()
                {
                    @Override
                    public void onFailure(Stream.Client stream, long error, Throwable failure)
                    {
                        streamFailureLatch.countDown();
                    }
                });
            }
        });

        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(streamFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)clientSession).isClosed());
        assertTrue(((HTTP3Session)serverSessionRef.get()).isClosed());
    }

    @Test
    public void testServerGracefulGoAway() throws Exception
    {
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverSessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
                stream.respond(new HeadersFrame(response, true));
                return null;
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
        });

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        // Send a graceful GOAWAY from the server.
        // Because the server had no pending streams, it will send also a non-graceful GOAWAY.
        serverSessionRef.get().goAway(true);

        assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverSessionRef.get()).isClosed());
        assertTrue(((HTTP3Session)clientSession).isClosed());
    }

    @Test
    public void testServerGracefulGoAwayWithStreamsServerClosesWhenLastStreamCloses() throws Exception
    {
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        AtomicReference<Stream.Server> serverStreamRef = new AtomicReference<>();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverStreamRef.set(stream);
                Session session = stream.getSession();
                serverSessionRef.set(session);

                // Send a graceful GOAWAY while processing a stream.
                session.goAway(true);

                return null;
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
        });

        // Wait for the graceful GOAWAY.
        assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));

        // Now the client cannot create new streams.
        CompletableFuture<Stream> streamCompletable = clientSession.newRequest(new HeadersFrame(newRequest("/"), true), null);
        assertThrows(ExecutionException.class, () -> streamCompletable.get(5, TimeUnit.SECONDS));

        // The client must not reply to a graceful GOAWAY.
        assertFalse(serverGoAwayLatch.await(1, TimeUnit.SECONDS));

        // Previous streams must complete successfully.
        Stream.Server serverStream = serverStreamRef.get();
        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
        serverStream.respond(new HeadersFrame(response, true));

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        // The server should have sent the GOAWAY after the last stream completed.

        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverSessionRef.get()).isClosed());
        assertTrue(((HTTP3Session)clientSession).isClosed());
    }

    @Test
    public void testClientGoAwayWithStreamsServerClosesWhenLastStreamCloses() throws Exception
    {
        AtomicReference<Stream.Server> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverStreamRef.set(stream);
                serverStreamLatch.countDown();
                return null;
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
        });

        assertTrue(serverStreamLatch.await(5, TimeUnit.SECONDS));

        // The client sends a GOAWAY.
        clientSession.goAway(false);

        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));

        // The client must not receive a GOAWAY until the all streams are completed.
        assertFalse(clientGoAwayLatch.await(1, TimeUnit.SECONDS));

        // Complete the stream.
        Stream.Server serverStream = serverStreamRef.get();
        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
        serverStream.respond(new HeadersFrame(response, true));

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverStreamRef.get().getSession()).isClosed());
        assertTrue(((HTTP3Session)clientSession).isClosed());
    }

    @Test
    public void testServerGracefulGoAwayWithStreamsClientGoAwayServerClosesWhenLastStreamCloses() throws Exception
    {
        AtomicReference<Stream.Server> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverStreamRef.set(stream);
                serverStreamLatch.countDown();

                // Send a graceful GOAWAY while processing a stream.
                stream.getSession().goAway(true);

                return null;
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
                    session.goAway(false);
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
        });

        // The server has a pending stream, so it does not send the non-graceful GOAWAY yet.
        assertFalse(clientGoAwayLatch.await(1, TimeUnit.SECONDS));

        // Complete the stream, the server should send the non-graceful GOAWAY.
        Stream.Server serverStream = serverStreamRef.get();
        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
        serverStream.respond(new HeadersFrame(response, true));

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

    @Test
    public void testClientGracefulGoAwayWithStreamsServerGracefulGoAwayServerClosesWhenLastStreamCloses() throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverRequestLatch = new CountDownLatch(1);
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server serverStream, HeadersFrame frame)
            {
                serverStreamRef.set(serverStream);
                serverStream.demand();
                serverRequestLatch.countDown();
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        Stream.Data data = stream.readData();
                        if (data != null)
                            data.release();
                        if (data != null && data.isLast())
                        {
                            MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
                            serverStream.respond(new HeadersFrame(response, true));
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
                    session.goAway(true);
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
        Stream clientStream = clientSession.newRequest(new HeadersFrame(newRequest("/"), false), new Stream.Client.Listener() {})
            .get(5, TimeUnit.SECONDS);

        assertTrue(serverRequestLatch.await(5, TimeUnit.SECONDS));

        // Send a graceful GOAWAY from the client.
        clientSession.goAway(true);

        // The server should send a graceful GOAWAY.
        assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));

        // Complete the stream.
        clientStream.data(new DataFrame(BufferUtil.EMPTY_BUFFER, true));

        // Both client and server should send a non-graceful GOAWAY.
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverStreamRef.get().getSession()).isClosed());
        assertTrue(((HTTP3Session)clientSession).isClosed());
    }

    @Test
    public void testClientDisconnectServerCloses() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch settingsLatch = new CountDownLatch(2);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
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

        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        HTTP3SessionClient clientSession = (HTTP3SessionClient)newSession(new Session.Client.Listener()
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

        // Issue a network disconnection.
        clientSession.getProtocolSession().getQuicSession().getQuicConnection().close();

        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverSessionRef.get()).isClosed());
        assertTrue(clientSession.isClosed());
    }

    @Test
    public void testServerGracefulGoAwayClientDisconnectServerCloses() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch settingsLatch = new CountDownLatch(2);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
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

        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
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
                ((HTTP3Session)session).getProtocolSession().getQuicSession().getQuicConnection().close();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientDisconnectLatch.countDown();
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        // Send a graceful GOAWAY to the client.
        serverSessionRef.get().goAway(true);

        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverSessionRef.get()).isClosed());
        assertTrue(((HTTP3Session)clientSession).isClosed());
    }

    @Test
    public void testClientIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<HTTP3Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session session)
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

        await().atMost(1, TimeUnit.SECONDS).until(() -> clientSession.getProtocolSession().getQuicSession().getQuicConnection().getEndPoint().isOpen(), is(false));
    }

    @Test
    public void testServerIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverIdleTimeoutLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session session)
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

    @Test
    public void testServerGracefulGoAwayWithStreamsServerIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
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
                // Send a graceful GOAWAY.
                stream.getSession().goAway(true);
                return null;
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
        });

        assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));
        // Server idle timeout sends a non-graceful GOAWAY.
        assertTrue(clientFailureLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        HTTP3SessionServer serverSession = (HTTP3SessionServer)serverSessionRef.get();
        assertTrue(serverSession.isClosed());
        assertTrue(serverSession.getStreams().isEmpty());
        ServerQuicSession serverQuicSession = serverSession.getProtocolSession().getQuicSession();
        // While HTTP/3 is completely closed, QUIC may still be exchanging packets, so we need to await().
        await().atMost(1, TimeUnit.SECONDS).until(() -> serverQuicSession.getQuicStreamEndPoints().isEmpty());
        await().atMost(1, TimeUnit.SECONDS).until(() -> serverQuicSession.getQuicConnection().getQuicSessions().isEmpty());

        assertTrue(clientSession.isClosed());
        assertTrue(clientSession.getStreams().isEmpty());
        ClientQuicSession clientQuicSession = clientSession.getProtocolSession().getQuicSession();
        // While HTTP/3 is completely closed, QUIC may still be exchanging packets, so we need to await().
        await().atMost(1, TimeUnit.SECONDS).until(() -> clientQuicSession.getQuicStreamEndPoints().isEmpty());
        QuicConnection quicConnection = clientQuicSession.getQuicConnection();
        await().atMost(1, TimeUnit.SECONDS).until(() -> quicConnection.getQuicSessions().isEmpty());
        await().atMost(1, TimeUnit.SECONDS).until(() -> quicConnection.getEndPoint().isOpen(), is(false));
    }

    @Test
    public void testClientGracefulGoAwayWithStreamsServerIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverRequestLatch = new CountDownLatch(1);
        CountDownLatch serverGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
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
        });

        assertTrue(serverRequestLatch.await(5, TimeUnit.SECONDS));

        // Client sends a graceful GOAWAY.
        clientSession.goAway(true);

        assertTrue(serverGracefulGoAwayLatch.await(555, TimeUnit.SECONDS));
        assertTrue(streamFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(((HTTP3Session)serverSessionRef.get()).isClosed());
        assertTrue(clientSession.isClosed());
    }

    @Test
    public void testServerGoAwayWithStreamsThenDisconnect() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverSessionRef.set(stream.getSession());
                // Don't reply, don't reset the stream, just send the GOAWAY.
                stream.getSession().goAway(false);
                return null;
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

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), false), new Stream.Client.Listener()
        {
            @Override
            public void onFailure(Stream.Client stream, long error, Throwable failure)
            {
                clientFailureLatch.countDown();
            }
        });

        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));

        // Neither the client nor the server are finishing
        // the pending stream, so force the disconnect on the server.
        HTTP3Session serverSession = (HTTP3Session)serverSessionRef.get();
        serverSession.getProtocolSession().getQuicSession().getQuicConnection().close();

        // The server should reset all the pending streams.
        assertTrue(clientFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        assertTrue(serverSession.isClosed());
        assertTrue(clientSession.isClosed());
    }

    @Test
    public void testClientStop() throws Exception
    {
        CountDownLatch settingsLatch = new CountDownLatch(2);
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
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
        HTTP3Session clientSession = (HTTP3Session)newSession(new Session.Client.Listener()
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

        // Stopping the HttpClient will also stop the HTTP3Client.
        httpClient.stop();

        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(() -> clientSession.getProtocolSession().getQuicSession().getQuicConnection().getEndPoint().isOpen(), is(false));
    }

    @Test
    public void testServerStop() throws Exception
    {
        AtomicReference<HTTP3Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch settingsLatch = new CountDownLatch(2);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                serverSessionRef.set((HTTP3Session)session);
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
        HTTP3Session clientSession = (HTTP3Session)newSession(new Session.Client.Listener()
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

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        server.stop();

        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverDisconnectLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(() -> serverSessionRef.get().getProtocolSession().getQuicSession().getQuicConnection().getEndPoint().isOpen(), is(false));
        await().atMost(1, TimeUnit.SECONDS).until(() -> clientSession.getProtocolSession().getQuicSession().getQuicConnection().getEndPoint().isOpen(), is(false));
    }

    @Test
    public void testClientShutdown() throws Exception
    {
        AtomicReference<HTTP3Stream> serverStreamRef = new AtomicReference<>();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverStreamRef.set((HTTP3Stream)stream);
                stream.respond(new HeadersFrame(new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY), false));
                return null;
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
                Stream.Data data = stream.readData();
                if (data != null)
                {
                    data.release();
                    if (data.isLast())
                        dataLatch.countDown();
                }
                stream.demand();
            }
        });

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> shutdown = http3Client.shutdown();

        // Shutdown must not complete yet.
        assertThrows(TimeoutException.class, () -> shutdown.get(1, TimeUnit.SECONDS));

        // Complete the response.
        serverStreamRef.get().data(new DataFrame(BufferUtil.EMPTY_BUFFER, true));

        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
        shutdown.get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testServerShutdown() throws Exception
    {
        AtomicReference<HTTP3Stream> serverStreamRef = new AtomicReference<>();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverStreamRef.set((HTTP3Stream)stream);
                stream.respond(new HeadersFrame(new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY), false));
                return null;
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
                Stream.Data data = stream.readData();
                if (data != null)
                {
                    data.release();
                    if (data.isLast())
                        dataLatch.countDown();
                }
                stream.demand();
            }
        });

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> shutdown = connector.shutdown();
        // Shutdown must not complete yet.
        assertThrows(TimeoutException.class, () -> shutdown.get(1, TimeUnit.SECONDS));

        // Complete the response.
        serverStreamRef.get().data(new DataFrame(BufferUtil.EMPTY_BUFFER, true));

        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
        shutdown.get(5, TimeUnit.SECONDS);
    }
}
