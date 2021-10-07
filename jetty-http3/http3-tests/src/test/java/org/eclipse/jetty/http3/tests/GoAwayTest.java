//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.ErrorCode;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GoAwayTest extends AbstractClientServerTest
{
    @Test
    public void testClientGoAwayServerReplies() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
            {
                serverSessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
                stream.respond(new HeadersFrame(response, true));
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverLatch.countDown();
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientLatch.countDown();
            }
        });
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Listener()
        {
            @Override
            public void onResponse(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isLast() && response.getStatus() == HttpStatus.OK_200)
                    clientSession.goAway(false);
            }
        });

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)serverSessionRef.get())::isClosed);
        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)clientSession)::isClosed);
    }

    @Test
    public void testServerGoAwayWithInFlightStreamClientFailsStream() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverTerminateLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
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
            public void onTerminate(Session session)
            {
                serverTerminateLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientTerminateLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onTerminate(Session session)
            {
                clientTerminateLatch.countDown();
            }
        });

        CountDownLatch streamFailureLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Listener()
        {
            @Override
            public void onResponse(Stream stream, HeadersFrame frame)
            {
                // Simulate the server sending a GOAWAY while the client sends a second request.
                // The server sends a lastStreamId for the first request, and discards the second.
                serverSessionRef.get().goAway(false);
                // The client sends the second request and should eventually fail it
                // locally since it has a larger streamId, and the server discarded it.
                clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Listener()
                {
                    @Override
                    public void onFailure(Stream stream, long error, Throwable failure)
                    {
                        streamFailureLatch.countDown();
                    }
                });
            }
        });

        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(streamFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientTerminateLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverTerminateLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)clientSession)::isClosed);
        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)serverSessionRef.get())::isClosed);
    }

    @Test
    public void testServerGracefulGoAway() throws Exception
    {
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverTerminateLatch = new CountDownLatch(1);
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
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
            public void onTerminate(Session session)
            {
                serverTerminateLatch.countDown();
            }
        });

        CountDownLatch clientGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientTerminateLatch = new CountDownLatch(1);
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
            public void onTerminate(Session session)
            {
                clientTerminateLatch.countDown();
            }
        });
        CountDownLatch clientLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Listener()
        {
            @Override
            public void onResponse(Stream stream, HeadersFrame frame)
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
        assertTrue(clientTerminateLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverTerminateLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)serverSessionRef.get())::isClosed);
        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)clientSession)::isClosed);
    }

    @Test
    public void testServerGracefulGoAwayWithStreamsServerClosesWhenLastStreamCloses() throws Exception
    {
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverTerminateLatch = new CountDownLatch(1);
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
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
            public void onTerminate(Session session)
            {
                serverTerminateLatch.countDown();
            }
        });

        CountDownLatch clientGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientTerminateLatch = new CountDownLatch(1);
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
            public void onTerminate(Session session)
            {
                clientTerminateLatch.countDown();
            }
        });
        CountDownLatch clientLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Listener()
        {
            @Override
            public void onResponse(Stream stream, HeadersFrame frame)
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
        Stream serverStream = serverStreamRef.get();
        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
        serverStream.respond(new HeadersFrame(response, true));

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        // The server should have sent the GOAWAY after the last stream completed.

        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientTerminateLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverTerminateLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)serverSessionRef.get())::isClosed);
        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)clientSession)::isClosed);
    }

    @Test
    public void testClientGoAwayWithStreamsServerClosesWhenLastStreamCloses() throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverTerminateLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
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
            public void onTerminate(Session session)
            {
                serverTerminateLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientTerminateLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onTerminate(Session session)
            {
                clientTerminateLatch.countDown();
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Listener()
        {
            @Override
            public void onResponse(Stream stream, HeadersFrame frame)
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
        Stream serverStream = serverStreamRef.get();
        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
        serverStream.respond(new HeadersFrame(response, true));

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverTerminateLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientTerminateLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)serverStreamRef.get().getSession())::isClosed);
        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)clientSession)::isClosed);
    }

    @Test
    public void testServerGracefulGoAwayWithStreamsClientGoAwayServerClosesWhenLastStreamCloses() throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverTerminateLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
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
            public void onTerminate(Session session)
            {
                serverTerminateLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientTerminateLatch = new CountDownLatch(1);
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
            public void onTerminate(Session session)
            {
                clientTerminateLatch.countDown();
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Listener()
        {
            @Override
            public void onResponse(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isLast() && response.getStatus() == HttpStatus.OK_200)
                    clientLatch.countDown();
            }
        });

        // The server has a pending stream, so it does not send the non-graceful GOAWAY yet.
        assertFalse(clientGoAwayLatch.await(1, TimeUnit.SECONDS));

        // Complete the stream, the server should send the non-graceful GOAWAY.
        Stream serverStream = serverStreamRef.get();
        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
        serverStream.respond(new HeadersFrame(response, true));

        // The server already received the client GOAWAY,
        // so completing the last stream produces a close event.
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverTerminateLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
        // The client should receive the server non-graceful GOAWAY.
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientTerminateLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)serverStreamRef.get().getSession())::isClosed);
        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)clientSession)::isClosed);
    }

    @Test
    public void testClientGracefulGoAwayWithStreamsServerGracefulGoAwayServerClosesWhenLastStreamCloses() throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverTerminateLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
            {
                serverStreamRef.set(stream);
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();
                        data.complete();
                        if (data.isLast())
                        {
                            MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
                            stream.respond(new HeadersFrame(response, true));
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
            public void onTerminate(Session session)
            {
                serverTerminateLatch.countDown();
            }
        });

        CountDownLatch clientGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientTerminateLatch = new CountDownLatch(1);
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
            public void onTerminate(Session session)
            {
                clientTerminateLatch.countDown();
            }
        });
        Stream clientStream = clientSession.newRequest(new HeadersFrame(newRequest("/"), false), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS);

        // Send a graceful GOAWAY from the client.
        clientSession.goAway(true);

        // The server should send a graceful GOAWAY.
        assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));

        // Complete the stream.
        clientStream.data(new DataFrame(BufferUtil.EMPTY_BUFFER, true));

        // Both client and server should send a non-graceful GOAWAY.
        assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverTerminateLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientTerminateLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)serverStreamRef.get().getSession())::isClosed);
        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)clientSession)::isClosed);
    }

    @Test
    public void testClientShutdownServerCloses() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch settingsLatch = new CountDownLatch(2);
        CountDownLatch serverTerminateLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                serverSessionRef.set(session);
                settingsLatch.countDown();
            }

            @Override
            public void onTerminate(Session session)
            {
                serverTerminateLatch.countDown();
            }
        });

        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        // Issue a network close.
        ((HTTP3Session)clientSession).close(ErrorCode.NO_ERROR.code(), "close");

        assertTrue(serverTerminateLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)serverSessionRef.get())::isClosed);
        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)clientSession)::isClosed);
    }

    @Test
    public void testServerGracefulGoAwayClientShutdownServerCloses() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch settingsLatch = new CountDownLatch(2);
        CountDownLatch serverTerminateLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                serverSessionRef.set(session);
                settingsLatch.countDown();
            }

            @Override
            public void onTerminate(Session session)
            {
                serverTerminateLatch.countDown();
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
                // Reply to the graceful GOAWAY from the server with a network close.
                ((HTTP3Session)session).close(ErrorCode.NO_ERROR.code(), "close");
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        // Send a graceful GOAWAY to the client.
        serverSessionRef.get().goAway(true);

        assertTrue(serverTerminateLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)serverSessionRef.get())::isClosed);
        await().atMost(1, TimeUnit.SECONDS).until(((HTTP3Session)clientSession)::isClosed);
    }

/*
    @Test
    public void testServerIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverIdleTimeoutLatch = new CountDownLatch(1);
        CountDownLatch serverTerminateLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session session)
            {
                serverSessionRef.set(session);
                ((HTTP2Session)session).getEndPoint().setIdleTimeout(idleTimeout);
            }

            @Override
            public boolean onIdleTimeout(Session session)
            {
                serverIdleTimeoutLatch.countDown();
                return true;
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverTerminateLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientTerminateLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (!frame.isGraceful())
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientTerminateLatch.countDown();
            }
        });

        assertTrue(serverIdleTimeoutLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // Server should send a GOAWAY to the client.
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        // The client replied to server's GOAWAY, but the server already closed.
        assertTrue(clientTerminateLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverTerminateLatch.await(5, TimeUnit.SECONDS));

        assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
        assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }

    @Test
    public void testServerGracefulGoAwayWithStreamsServerIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverTerminateLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session session)
            {
                serverSessionRef.set(session);
                ((HTTP2Session)session).getEndPoint().setIdleTimeout(idleTimeout);
            }

            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                // Send a graceful GOAWAY.
                ((HTTP2Session)stream.getSession()).goAway(GoAwayFrame.GRACEFUL, Callback.NOOP);
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverTerminateLatch.countDown();
            }
        });

        CountDownLatch clientGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientTerminateLatch = new CountDownLatch(1);
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
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientTerminateLatch.countDown();
            }
        });
        CountDownLatch clientResetLatch = new CountDownLatch(1);
        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        // Send request headers but not data.
        clientSession.newRequest(new HeadersFrame(request, null, false), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                clientResetLatch.countDown();
            }
        });

        assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));
        // Server idle timeout sends a non-graceful GOAWAY.
        assertTrue(clientResetLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverTerminateLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientTerminateLatch.await(5, TimeUnit.SECONDS));

        assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
        assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }

    @Test
    public void testClientGracefulGoAwayWithStreamsServerIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverTerminateLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session session)
            {
                serverSessionRef.set(session);
                ((HTTP2Session)session).getEndPoint().setIdleTimeout(idleTimeout);
            }

            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                    serverGracefulGoAwayLatch.countDown();
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverTerminateLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientTerminateLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientTerminateLatch.countDown();
            }
        });
        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        CountDownLatch streamResetLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(request, null, false), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                streamResetLatch.countDown();
            }
        });

        // Client sends a graceful GOAWAY.
        ((HTTP2Session)clientSession).goAway(GoAwayFrame.GRACEFUL, Callback.NOOP);

        assertTrue(serverGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(streamResetLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(serverTerminateLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientTerminateLatch.await(5, TimeUnit.SECONDS));

        assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
        assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }

    @Test
    public void testServerGoAwayWithStreamsThenStop() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverTerminateLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
            {
                serverSessionRef.set(stream.getSession());
                // Don't reply, don't reset the stream, just send the GOAWAY.
                stream.getSession().close(ErrorCode.NO_ERROR.code, "close", Callback.NOOP);
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverTerminateLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientTerminateLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientTerminateLatch.countDown();
            }
        });

        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        CountDownLatch clientResetLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(request, null, false), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                clientResetLatch.countDown();
            }
        });

        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));

        // Neither the client nor the server are finishing
        // the pending stream, so force the stop on the server.
        LifeCycle.stop(serverSessionRef.get());

        // The server should reset all the pending streams.
        assertTrue(clientResetLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverTerminateLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientTerminateLatch.await(5, TimeUnit.SECONDS));

        assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
        assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }
 */
}
