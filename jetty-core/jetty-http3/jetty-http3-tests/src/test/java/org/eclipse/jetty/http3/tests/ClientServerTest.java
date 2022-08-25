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

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.internal.HTTP3SessionClient;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.http3.server.AbstractHTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.internal.HTTP3SessionServer;
import org.eclipse.jetty.quic.client.ClientQuicSession;
import org.eclipse.jetty.quic.common.QuicSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO check that the Awaitility usage is the right thing to do.
public class ClientServerTest extends AbstractClientServerTest
{
    @Test
    public void testConnectTriggersSettingsFrame() throws Exception
    {
        CountDownLatch serverPrefaceLatch = new CountDownLatch(1);
        CountDownLatch serverSettingsLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
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

    @Test
    public void testSettings() throws Exception
    {
        Map.Entry<Long, Long> maxTableCapacity = new AbstractMap.SimpleEntry<>(SettingsFrame.MAX_TABLE_CAPACITY, 1024L);
        Map.Entry<Long, Long> maxHeaderSize = new AbstractMap.SimpleEntry<>(SettingsFrame.MAX_FIELD_SECTION_SIZE, 2048L);
        Map.Entry<Long, Long> maxBlockedStreams = new AbstractMap.SimpleEntry<>(SettingsFrame.MAX_BLOCKED_STREAMS, 16L);
        CountDownLatch settingsLatch = new CountDownLatch(2);
        AtomicReference<HTTP3SessionServer> serverSessionRef = new AtomicReference<>();
        start(new Session.Server.Listener()
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
        assertEquals(maxTableCapacity.getValue(), serverSession.getProtocolSession().getQpackEncoder().getCapacity());
        assertEquals(maxBlockedStreams.getValue(), serverSession.getProtocolSession().getQpackEncoder().getMaxBlockedStreams());
        assertEquals(maxBlockedStreams.getValue(), serverSession.getProtocolSession().getQpackDecoder().getMaxBlockedStreams());
        assertEquals(maxHeaderSize.getValue(), serverSession.getProtocolSession().getQpackDecoder().getMaxHeaderSize());

        assertEquals(maxTableCapacity.getValue(), clientSession.getProtocolSession().getQpackEncoder().getCapacity());
        assertEquals(maxBlockedStreams.getValue(), clientSession.getProtocolSession().getQpackEncoder().getMaxBlockedStreams());
        assertEquals(maxBlockedStreams.getValue(), clientSession.getProtocolSession().getQpackDecoder().getMaxBlockedStreams());
        assertEquals(maxHeaderSize.getValue(), clientSession.getProtocolSession().getQpackDecoder().getMaxHeaderSize());
    }

    @Test
    public void testGETThenResponseWithoutContent() throws Exception
    {
        AtomicReference<HTTP3Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverRequestLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverSessionRef.set((HTTP3Session)stream.getSession());
                serverRequestLatch.countDown();
                // Send the response.
                stream.respond(new HeadersFrame(new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY), true));
                // Not interested in request data.
                return null;
            }
        });

        HTTP3SessionClient clientSession = (HTTP3SessionClient)newSession(new Session.Client.Listener() {});

        CountDownLatch clientResponseLatch = new CountDownLatch(1);
        HeadersFrame frame = new HeadersFrame(newRequest("/"), true);
        Stream stream = clientSession.newRequest(frame, new Stream.Client.Listener()
            {
                @Override
                public void onResponse(Stream.Client stream, HeadersFrame frame)
                {
                    clientResponseLatch.countDown();
                }
            })
            .get(5, TimeUnit.SECONDS);
        assertNotNull(stream);

        assertTrue(serverRequestLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientResponseLatch.await(5, TimeUnit.SECONDS));

        HTTP3Session serverSession = serverSessionRef.get();
        await().atMost(5, TimeUnit.SECONDS).until(() -> serverSession.getStreams().isEmpty()); // onRequest is called *before* the serverSession's streams collection is cleaned up -> racy
        await().atMost(5, TimeUnit.SECONDS).until(() -> clientSession.getStreams().isEmpty()); // onResponse is called *before* the clientSession's streams collection is cleaned up -> racy

        QuicSession serverQuicSession = serverSession.getProtocolSession().getQuicSession();
        assertTrue(serverQuicSession.getQuicStreamEndPoints().stream()
            .noneMatch(endPoint -> endPoint.getStreamId() == stream.getId()));

        ClientQuicSession clientQuicSession = clientSession.getProtocolSession().getQuicSession();
        assertTrue(clientQuicSession.getQuicStreamEndPoints().stream()
            .noneMatch(endPoint -> endPoint.getStreamId() == stream.getId()));
    }

    @Test
    public void testDiscardRequestContent() throws Exception
    {
        AtomicReference<CountDownLatch> serverLatch = new AtomicReference<>(new CountDownLatch(1));
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                // Send the response.
                stream.respond(new HeadersFrame(new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY), false));
                stream.demand();
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        // FlowControl acknowledged already.
                        Stream.Data data = stream.readData();
                        if (data == null)
                        {
                            // Call me again when you have data.
                            stream.demand();
                            return;
                        }
                        // Recycle the ByteBuffer in data.frame.
                        data.release();
                        if (data.isLast())
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
        Stream stream1 = session.newRequest(frame, streamListener)
            .get(5, TimeUnit.SECONDS);
        stream1.data(new DataFrame(ByteBuffer.allocate(8192), true));

        assertTrue(clientLatch.get().await(5, TimeUnit.SECONDS));
        assertTrue(serverLatch.get().await(5, TimeUnit.SECONDS));

        // Send another request, but with 2 chunks of data separated by some time.
        serverLatch.set(new CountDownLatch(1));
        clientLatch.set(new CountDownLatch(1));
        Stream stream2 = session.newRequest(frame, streamListener).get(5, TimeUnit.SECONDS);
        stream2.data(new DataFrame(ByteBuffer.allocate(3 * 1024), false));
        // Wait some time before sending the second chunk.
        Thread.sleep(500);
        stream2.data(new DataFrame(ByteBuffer.allocate(5 * 1024), true));

        assertTrue(clientLatch.get().await(5, TimeUnit.SECONDS));
        assertTrue(serverLatch.get().await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ValueSource(ints = {1024, 10 * 1024, 100 * 1024, 1000 * 1024})
    public void testEchoRequestContentAsResponseContent(int length) throws Exception
    {
        AtomicReference<HTTP3Session> serverSessionRef = new AtomicReference<>();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverSessionRef.set((HTTP3Session)stream.getSession());
                // Send the response headers.
                stream.respond(new HeadersFrame(new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY), false));
                stream.demand();
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        // Read data.
                        Stream.Data data = stream.readData();
                        if (data == null)
                        {
                            stream.demand();
                            return;
                        }
                        // Echo it back, then demand only when the write is finished.
                        stream.data(new DataFrame(data.getByteBuffer(), data.isLast()))
                            // Always release.
                            .whenComplete((s, x) -> data.release())
                            // Demand only if successful and not last.
                            .thenRun(() ->
                            {
                                if (!data.isLast())
                                    stream.demand();
                            });
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
        Stream stream = clientSession.newRequest(frame, new Stream.Client.Listener()
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
                    Stream.Data data = stream.readData();
                    if (data == null)
                    {
                        stream.demand();
                        return;
                    }
                    // Consume data.
                    byteBuffer.put(data.getByteBuffer());
                    data.release();
                    if (data.isLast())
                        clientDataLatch.countDown();
                    else
                        stream.demand();
                }
            })
            .get(5, TimeUnit.SECONDS);
        stream.data(new DataFrame(ByteBuffer.wrap(bytesSent), true));

        assertTrue(clientResponseLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDataLatch.await(15, TimeUnit.SECONDS));
        assertArrayEquals(bytesSent, bytesReceived);

        HTTP3Session serverSession = serverSessionRef.get();
        assertTrue(serverSession.getStreams().isEmpty());
        assertTrue(clientSession.getStreams().isEmpty());

        QuicSession serverQuicSession = serverSession.getProtocolSession().getQuicSession();
        assertTrue(serverQuicSession.getQuicStreamEndPoints().stream()
            .noneMatch(endPoint -> endPoint.getStreamId() == stream.getId()));

        ClientQuicSession clientQuicSession = clientSession.getProtocolSession().getQuicSession();
        assertTrue(clientQuicSession.getQuicStreamEndPoints().stream()
            .noneMatch(endPoint -> endPoint.getStreamId() == stream.getId()));
    }

    @Test
    public void testRequestHeadersTooLarge() throws Exception
    {
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                stream.respond(new HeadersFrame(new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY), true));
                return null;
            }
        });

        int maxRequestHeadersSize = 128;
        http3Client.getHTTP3Configuration().setMaxRequestHeadersSize(maxRequestHeadersSize);
        Session.Client clientSession = newSession(new Session.Client.Listener() {});

        CountDownLatch requestFailureLatch = new CountDownLatch(1);
        HttpFields largeHeaders = HttpFields.build().put("too-large", "x".repeat(2 * maxRequestHeadersSize));
        clientSession.newRequest(new HeadersFrame(newRequest(HttpMethod.GET, "/", largeHeaders), true), new Stream.Client.Listener() {})
            .whenComplete((s, x) ->
            {
                // The HTTP3Stream was created, but the application cannot access
                // it, so the implementation must remove it from the HTTP3Session.
                // See below the difference with the server.
                if (x != null)
                    requestFailureLatch.countDown();
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
        });

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testResponseHeadersTooLarge() throws Exception
    {
        int maxResponseHeadersSize = 128;
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch responseFailureLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverSessionRef.set(stream.getSession());
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if ("/large".equals(request.getURI().getPath()))
                {
                    HttpFields largeHeaders = HttpFields.build().put("too-large", "x".repeat(2 * maxResponseHeadersSize));
                    stream.respond(new HeadersFrame(new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, largeHeaders), true))
                        .whenComplete((s, x) ->
                        {
                            // The response could not be generated, but the stream is still valid.
                            // Applications may try to send a smaller response here,
                            // so the implementation must not remove the stream.
                            if (x != null)
                            {
                                // In this test, we give up if there is an error.
                                stream.reset(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x);
                                responseFailureLatch.countDown();
                            }
                        });
                }
                else
                {
                    stream.respond(new HeadersFrame(new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY), true));
                }
                return null;
            }
        });
        AbstractHTTP3ServerConnectionFactory h3 = connector.getConnectionFactory(AbstractHTTP3ServerConnectionFactory.class);
        assertNotNull(h3);
        h3.getHTTP3Configuration().setMaxResponseHeadersSize(maxResponseHeadersSize);

        Session.Client clientSession = newSession(new Session.Client.Listener() {});

        CountDownLatch streamFailureLatch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/large"), true), new Stream.Client.Listener()
            {
                @Override
                public void onFailure(Stream.Client stream, long error, Throwable failure)
                {
                    streamFailureLatch.countDown();
                }
            });

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
        });

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    // TODO: write a test calling readData() from onRequest() (not from onDataAvailable()).
}
