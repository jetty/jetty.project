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

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.HTTP3Configuration;
import org.eclipse.jetty.http3.HTTP3Session;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3SessionClient;
import org.eclipse.jetty.http3.client.internal.ClientHTTP3Session;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.server.AbstractHTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.internal.HTTP3SessionServer;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientServerTest extends AbstractClientServerTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testConnectTriggersSettingsFrame(TransportType transportType) throws Exception
    {
        CountDownLatch serverPrefaceLatch = new CountDownLatch(1);
        CountDownLatch serverSettingsLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Map<Long, Long> onPreface(Session session)
            {
                serverPrefaceLatch.countDown();
                return Map.of();
            }

            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                serverSettingsLatch.countDown();
            }
        });

        CountDownLatch clientPrefaceLatch = new CountDownLatch(1);
        CountDownLatch clientSettingsLatch = new CountDownLatch(1);
        Session.Client session = newSession(new Session.Client.Listener()
        {
            @Override
            public Map<Long, Long> onPreface(Session session)
            {
                clientPrefaceLatch.countDown();
                return Map.of();
            }

            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                clientSettingsLatch.countDown();
            }
        });
        assertNotNull(session);

        assertTrue(serverSettingsLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientSettingsLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSettings(TransportType transportType) throws Exception
    {
        Map.Entry<Long, Long> maxTableCapacity = new AbstractMap.SimpleEntry<>(SettingsFrame.MAX_TABLE_CAPACITY, 1024L);
        Map.Entry<Long, Long> maxHeaderSize = new AbstractMap.SimpleEntry<>(SettingsFrame.MAX_FIELD_SECTION_SIZE, 2048L);
        Map.Entry<Long, Long> maxBlockedStreams = new AbstractMap.SimpleEntry<>(SettingsFrame.MAX_BLOCKED_STREAMS, 16L);
        CountDownLatch settingsLatch = new CountDownLatch(2);
        AtomicReference<HTTP3SessionServer> serverSessionRef = new AtomicReference<>();
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Map<Long, Long> onPreface(Session session)
            {
                serverSessionRef.set((HTTP3SessionServer)session);
                return Map.ofEntries(maxTableCapacity, maxHeaderSize, maxBlockedStreams);
            }

            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });

        HTTP3SessionClient clientSession = (HTTP3SessionClient)newSession(new Session.Client.Listener()
        {
            @Override
            public Map<Long, Long> onPreface(Session session)
            {
                return Map.ofEntries(maxTableCapacity, maxHeaderSize, maxBlockedStreams);
            }

            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        HTTP3SessionServer serverSession = serverSessionRef.get();
        assertEquals(maxTableCapacity.getValue(), serverSession.getQpackEncoder().getMaxTableCapacity());
        assertEquals(maxBlockedStreams.getValue(), serverSession.getQpackEncoder().getMaxBlockedStreams());
        assertEquals(maxBlockedStreams.getValue(), serverSession.getQpackDecoder().getMaxBlockedStreams());
        assertEquals(maxHeaderSize.getValue(), serverSession.getQpackDecoder().getMaxHeadersSize());

        assertEquals(maxTableCapacity.getValue(), clientSession.getQpackEncoder().getMaxTableCapacity());
        assertEquals(maxBlockedStreams.getValue(), clientSession.getQpackEncoder().getMaxBlockedStreams());
        assertEquals(maxBlockedStreams.getValue(), clientSession.getQpackDecoder().getMaxBlockedStreams());
        assertEquals(maxHeaderSize.getValue(), clientSession.getQpackDecoder().getMaxHeadersSize());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testGETThenResponseWithoutContent(TransportType transportType) throws Exception
    {
        AtomicReference<HTTP3Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverRequestLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                serverSessionRef.set((HTTP3Session)session);
                serverRequestLatch.countDown();
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        // Send the response.
                        stream.respond(new HeadersFrame(new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY), true), Promise.Invocable.noop());
                        // Not interested in request data.
                    }
                };
            }
        });

        HTTP3SessionClient clientSession = (HTTP3SessionClient)newSession(new Session.Client.Listener() {});

        CountDownLatch clientResponseLatch = new CountDownLatch(1);
        HeadersFrame frame = new HeadersFrame(newRequest("/"), true);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> clientSession.newRequest(frame, new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                clientResponseLatch.countDown();
            }
        }, p));
        assertNotNull(stream);

        assertTrue(serverRequestLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientResponseLatch.await(5, TimeUnit.SECONDS));

        HTTP3Session serverSession = serverSessionRef.get();
        await().atMost(5, TimeUnit.SECONDS).until(() -> serverSession.getStreams().isEmpty()); // onRequest is called *before* the serverSession's streams collection is cleaned up -> racy
        await().atMost(5, TimeUnit.SECONDS).until(() -> clientSession.getStreams().isEmpty()); // onResponse is called *before* the clientSession's streams collection is cleaned up -> racy

        ProtocolSession serverProtocolSession = serverSession.getProtocolSession();
        assertTrue(serverProtocolSession.getStreamEndPoints().stream()
            .noneMatch(endPoint -> endPoint.getStream().getId() == stream.getId()));

        ClientHTTP3Session clientProtocolSession = (ClientHTTP3Session)clientSession.getProtocolSession();
        assertTrue(clientProtocolSession.getStreamEndPoints().stream()
            .noneMatch(endPoint -> endPoint.getStream().getId() == stream.getId()));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDiscardRequestContent(TransportType transportType) throws Exception
    {
        AtomicReference<CountDownLatch> serverLatch = new AtomicReference<>(new CountDownLatch(1));
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
                        // Send the response.
                        stream.respond(new HeadersFrame(new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY), false), Promise.Invocable.noop());
                        stream.demand();
                    }

                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        // FlowControl acknowledged already.
                        Content.Chunk chunk = stream.read();
                        if (chunk == null)
                        {
                            // Call me again when you have data.
                            stream.demand();
                            return;
                        }
                        // Recycle the ByteBuffer in the chunk.
                        chunk.release();
                        if (chunk.isLast())
                            serverLatch.get().countDown();
                        else
                            stream.demand();
                    }
                };
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        AtomicReference<CountDownLatch> clientLatch = new AtomicReference<>(new CountDownLatch(1));
        HeadersFrame frame = new HeadersFrame(newRequest(HttpMethod.POST, "/"), false);
        Stream.Client.Listener streamListener = new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                clientLatch.get().countDown();
            }
        };
        Stream stream1 = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(frame, streamListener, p));
        stream1.data(new DataFrame(ReadableBuffer.allocate(8192, false), true), Promise.Invocable.noop());

        assertTrue(clientLatch.get().await(5, TimeUnit.SECONDS));
        assertTrue(serverLatch.get().await(5, TimeUnit.SECONDS));

        // Send another request, but with 2 chunks of data separated by some time.
        serverLatch.set(new CountDownLatch(1));
        clientLatch.set(new CountDownLatch(1));
        Stream stream2 = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(frame, streamListener, p));
        stream2.data(new DataFrame(ReadableBuffer.allocate(3 * 1024, false), false), Promise.Invocable.noop());
        // Wait some time before sending the second chunk.
        Thread.sleep(500);
        stream2.data(new DataFrame(ReadableBuffer.allocate(5 * 1024, false), true), Promise.Invocable.noop());

        assertTrue(clientLatch.get().await(5, TimeUnit.SECONDS));
        assertTrue(serverLatch.get().await(5, TimeUnit.SECONDS));
    }

    public static java.util.stream.Stream<Arguments> transportsAndLengths()
    {
        return transports().stream()
            .flatMap(transportType -> IntStream.of(1024, 10 * 1024, 100 * 1024, 1000 * 1024)
                .mapToObj(length -> Arguments.of(transportType, length)));
    }

    @ParameterizedTest
    @MethodSource("transportsAndLengths")
    public void testEchoRequestContentAsResponseContent(TransportType transportType, int length) throws Exception
    {
        AtomicReference<HTTP3Session> serverSessionRef = new AtomicReference<>();
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                serverSessionRef.set((HTTP3Session)session);
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        // Send the response headers.
                        stream.respond(new HeadersFrame(new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY), false), Promise.Invocable.noop());
                        stream.demand();
                    }

                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        // Read data.
                        Content.Chunk chunk = stream.read();
                        if (chunk == null)
                        {
                            stream.demand();
                            return;
                        }
                        // Echo it back, then release, then demand only when the write is finished.
                        stream.data(new DataFrame(ReadableBuffer.wrap(chunk.getByteBuffer()), chunk.isLast()), Promise.Invocable.from(chunk::release, new Promise.Invocable.NonBlocking<>()
                        {
                            @Override
                            public void succeeded(Stream result)
                            {
                                // Demand only if successful and not last.
                                if (!chunk.isLast())
                                    stream.demand();
                            }
                        }));
                    }
                };
            }
        });

        HTTP3SessionClient clientSession = (HTTP3SessionClient)newSession(new Session.Client.Listener() {});

        CountDownLatch clientResponseLatch = new CountDownLatch(1);
        HeadersFrame frame = new HeadersFrame(newRequest("/"), false);
        byte[] bytesSent = new byte[length];
        new Random().nextBytes(bytesSent);
        byte[] bytesReceived = new byte[bytesSent.length];
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytesReceived);
        CountDownLatch clientDataLatch = new CountDownLatch(1);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> clientSession.newRequest(frame, new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                clientResponseLatch.countDown();
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream.Client stream)
            {
                // Read data.
                Content.Chunk chunk = stream.read();
                if (chunk == null)
                {
                    stream.demand();
                    return;
                }
                // Consume data.
                byteBuffer.put(chunk.getByteBuffer());
                chunk.release();
                if (chunk.isLast())
                    clientDataLatch.countDown();
                else
                    stream.demand();
            }
        }, p));
        stream.data(new DataFrame(ReadableBuffer.wrap(bytesSent), true), Promise.Invocable.noop());

        assertTrue(clientResponseLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDataLatch.await(15, TimeUnit.SECONDS));
        assertArrayEquals(bytesSent, bytesReceived);

        HTTP3Session serverSession = serverSessionRef.get();
        await().atMost(5, TimeUnit.SECONDS).until(() -> serverSession.getStreams().isEmpty());
        await().atMost(5, TimeUnit.SECONDS).until(() -> clientSession.getStreams().isEmpty());

        ProtocolSession serverProtocolSession = serverSession.getProtocolSession();
        assertTrue(serverProtocolSession.getStreamEndPoints().stream()
            .noneMatch(endPoint -> endPoint.getStream().getId() == stream.getId()));

        ClientHTTP3Session clientProtocolSession = (ClientHTTP3Session)clientSession.getProtocolSession();
        assertTrue(clientProtocolSession.getStreamEndPoints().stream()
            .noneMatch(endPoint -> endPoint.getStream().getId() == stream.getId()));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestHeadersTooLarge(TransportType transportType) throws Exception
    {
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
                        stream.respond(new HeadersFrame(new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY), true), Promise.Invocable.noop());
                    }
                };
            }
        });

        int maxRequestHeadersSize = 256;
        HTTP3Configuration http3Configuration = http3Client.getHTTP3Configuration();
        http3Configuration.setMaxRequestHeadersSize(maxRequestHeadersSize);
        // Disable the dynamic table, otherwise the large header
        // is sent as string literal on the encoder stream, rather than the message stream.
        http3Configuration.setMaxEncoderTableCapacity(0);
        CountDownLatch settingsLatch = new CountDownLatch(1);
        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });
        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch requestFailureLatch = new CountDownLatch(1);
        HttpFields largeHeaders = HttpFields.build().put("too-large", "x".repeat(2 * maxRequestHeadersSize));
        clientSession.newRequest(new HeadersFrame(newRequest(HttpMethod.GET, "/", largeHeaders), true), new Stream.Client.Listener() {}, new Promise.Invocable.NonBlocking<>()
        {
            @Override
            public void failed(Throwable x)
            {
                // The HTTP3Stream was created, but the application cannot access
                // it, so the implementation must remove it from the HTTP3Session.
                // See below the difference with the server.
                requestFailureLatch.countDown();
            }
        });

        assertTrue(requestFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientSession.getStreams().isEmpty());

        // Verify that the connection is still good.
        CountDownLatch responseLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                responseLatch.countDown();
            }
        }, Promise.Invocable.noop());

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testResponseHeadersTooLarge(TransportType transportType) throws Exception
    {
        int maxResponseHeadersSize = 256;
        CountDownLatch settingsLatch = new CountDownLatch(2);
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch responseFailureLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                serverSessionRef.set(session);
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        MetaData.Request request = (MetaData.Request)frame.getMetaData();
                        if ("/large".equals(request.getHttpURI().getPath()))
                        {
                            HttpFields largeHeaders = HttpFields.build().put("too-large", "x".repeat(2 * maxResponseHeadersSize));
                            stream.respond(new HeadersFrame(new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, largeHeaders), true), new Promise.Invocable.NonBlocking<>()
                            {
                                @Override
                                public void failed(Throwable x)
                                {
                                    responseFailureLatch.countDown();
                                }
                            });
                        }
                        else
                        {
                            stream.respond(new HeadersFrame(new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY), true), Promise.Invocable.noop());
                        }
                    }
                };
            }
        });
        AbstractHTTP3ServerConnectionFactory h3 = connector.getConnectionFactory(AbstractHTTP3ServerConnectionFactory.class);
        assertNotNull(h3);
        HTTP3Configuration http3Configuration = h3.getHTTP3Configuration();
        // Disable the dynamic table, otherwise the large header
        // is sent as string literal on the encoder stream, rather than the message stream.
        http3Configuration.setMaxEncoderTableCapacity(0);
        http3Configuration.setMaxResponseHeadersSize(maxResponseHeadersSize);

        Session.Client clientSession = newSession(new Session.Client.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch streamFailureLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/large"), true), new Stream.Client.Listener()
        {
            @Override
            public void onFailure(Stream.Client stream, long error, Throwable failure)
            {
                streamFailureLatch.countDown();
            }
        }, Promise.Invocable.noop());

        assertTrue(responseFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(streamFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverSessionRef.get().getStreams().isEmpty());
        await().atMost(5, TimeUnit.SECONDS).until(() -> clientSession.getStreams().isEmpty()); // onFailure is called *before* the clientSession's streams collection is cleaned up -> racy

        // Verify that the connection is still good.
        CountDownLatch responseLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                responseLatch.countDown();
            }
        }, Promise.Invocable.noop());

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testHeadersThenTrailers(TransportType transportType) throws Exception
    {
        CountDownLatch requestLatch = new CountDownLatch(1);
        CountDownLatch trailerLatch = new CountDownLatch(1);
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
                        requestLatch.countDown();
                    }

                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        // Calling read() triggers the read+parse
                        // of the trailer, and returns EOF.
                        Content.Chunk chunk = stream.read();
                        if (chunk == null)
                        {
                            stream.demand();
                            return;
                        }
                        assertTrue(chunk.isLast());
                        assertFalse(chunk.getByteBuffer().hasRemaining());
                    }

                    @Override
                    public void onTrailer(Stream.Server stream, HeadersFrame frame)
                    {
                        trailerLatch.countDown();
                        stream.respond(new HeadersFrame(new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY), true), Promise.Invocable.noop());
                    }
                };
            }
        });

        Session.Client clientSession = newSession(new Session.Client.Listener() {});

        CountDownLatch responseLatch = new CountDownLatch(1);
        Stream clientStream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> clientSession.newRequest(new HeadersFrame(newRequest("/large"), false), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(HttpStatus.OK_200, response.getStatus());
                responseLatch.countDown();
            }
        }, p));

        assertTrue(requestLatch.await(5, TimeUnit.SECONDS));

        clientStream.trailer(new HeadersFrame(new MetaData(HttpVersion.HTTP_3, HttpFields.EMPTY), true), Promise.Invocable.noop());

        assertTrue(trailerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testReadDataFromOnRequestWithoutDemanding(TransportType transportType) throws Exception
    {
        CountDownLatch requestLatch = new CountDownLatch(1);
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
                        requestLatch.countDown();
                        // This thread cannot be blocked otherwise we never
                        // read from the network what the client is sending.
                        // Therefore, spawn a new thread to read the content
                        // in a spin loop without calling demand().
                        new Thread(() -> readWithoutDemanding(stream)).start();
                    }

                    private void readWithoutDemanding(Stream.Server stream)
                    {
                        try
                        {
                            while (true)
                            {
                                Content.Chunk chunk = stream.read();
                                if (chunk == null)
                                {
                                    Thread.sleep(100);
                                    continue;
                                }
                                chunk.release();
                                if (chunk.isLast())
                                {
                                    stream.respond(new HeadersFrame(new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY), true), Promise.Invocable.noop());
                                    break;
                                }
                            }
                        }
                        catch (InterruptedException ignored)
                        {
                        }
                    }
                };
            }
        });

        Session.Client clientSession = newSession(new Session.Client.Listener() {});

        CountDownLatch responseLatch = new CountDownLatch(1);
        Stream clientStream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> clientSession.newRequest(new HeadersFrame(newRequest("/large"), false), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                responseLatch.countDown();
            }
        }, p));
        assertTrue(requestLatch.await(5, TimeUnit.SECONDS));

        Thread.sleep(500);
        clientStream.data(new DataFrame(ReadableBuffer.allocate(1024, false), false), Promise.Invocable.noop());

        Thread.sleep(500);
        clientStream.data(new DataFrame(ReadableBuffer.allocate(512, false), true), Promise.Invocable.noop());

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMissingNeededClientCertificateDeniesConnection(TransportType transportType) throws Exception
    {
        start(transportType, new Session.Server.Listener() {});
        serverSslContextFactory.setNeedClientAuth(true);

        CountDownLatch latch = new CountDownLatch(1);
        newSession(new Session.Client.Listener()
        {
            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                assertEquals(ErrorCode.CONNECTION_REFUSED_ERROR.code(), error);
                assertEquals("missing_peer_certificates", reason);
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
