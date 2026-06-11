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

package org.eclipse.jetty.quic.tests;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStream;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QuicTest extends AbstractQuicTest
{
    @Test
    public void testEstablishConnection() throws Exception
    {
        List<String> serverEvents = new ArrayList<>();
        start(() -> new Session.Listener()
        {
            @Override
            public void onPrepare(Session session, TransportParameters transportParameters)
            {
                serverEvents.add("prepare");
            }

            @Override
            public void onTransportParameters(Session session, TransportParameters parameters)
            {
                serverEvents.add("transportParameters");
            }

            @Override
            public void onOpen(Session session)
            {
                serverEvents.add("open");
            }
        });

        List<String> clientEvents = new ArrayList<>();
        Session session = Promise.Completable.<Session>with(p ->
            client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener()
            {
                @Override
                public void onPrepare(Session session, TransportParameters transportParameters)
                {
                    clientEvents.add("prepare");
                }

                @Override
                public void onTransportParameters(Session session, TransportParameters parameters)
                {
                    clientEvents.add("transportParameters");
                }

                @Override
                public void onOpen(Session session)
                {
                    clientEvents.add("open");
                }
            }, p)
        ).get(5, SECONDS);

        assertNotNull(session);

        List<String> expectedEvents = List.of("prepare", "transportParameters", "open");
        await().atMost(5, SECONDS).untilAsserted(() -> assertThat(serverEvents.toString(), serverEvents.size(), equalTo(3)));
        assertThat(serverEvents, equalTo(expectedEvents));
        assertThat(clientEvents, equalTo(expectedEvents));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1024, 1024 * 1024, 4 * 1024 * 1024})
    public void testEcho(int length) throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        start(() -> new Session.Listener()
        {
            @Override
            public void onOpen(Session session)
            {
                serverSessionRef.set(session);
            }

            @Override
            public Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
            {
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Content.Chunk chunk = stream.read();
                        if (chunk == null)
                        {
                            stream.demand();
                            return;
                        }
                        stream.data(chunk.isLast(), chunk, Promise.Invocable.from(NON_BLOCKING, (s, x) ->
                        {
                            if (x == null && !chunk.isLast())
                                s.demand();
                        }));
                    }
                };
            }
        });

        Session clientSession = Promise.Completable.<Session>with(p ->
            client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {}, p)
        ).get(5, SECONDS);

        CountDownLatch dataLatch = new CountDownLatch(1);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(client.getClientConnector().getByteBufferPool(), false, -1, 0, 0);
        long streamId = clientSession.newStreamId(true);
        Stream stream = clientSession.newStream(streamId, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream, boolean immediate)
            {
                while (true)
                {
                    Content.Chunk chunk = stream.read();
                    if (chunk == null)
                    {
                        stream.demand();
                        return;
                    }
                    accumulator.add(chunk);
                    if (chunk.isLast())
                    {
                        dataLatch.countDown();
                        break;
                    }
                }
            }
        });

        byte[] bytes = new byte[length];
        ThreadLocalRandom.current().nextBytes(bytes);
        stream.data(true, RetainableByteBuffer.wrap(ByteBuffer.wrap(bytes)), Promise.Invocable.noop());

        stream.demand();

        assertTrue(dataLatch.await(15, SECONDS));
        assertEquals(ByteBuffer.wrap(bytes), accumulator.getByteBuffer());

        Session serverSession = serverSessionRef.get();
        await().atMost(5, SECONDS).until(serverSession::getStreams, empty());
        await().atMost(5, SECONDS).until(clientSession::getStreams, empty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1024, 1024 * 1024, 8 * 1024 * 1024})
    public void testDownload(int length) throws Exception
    {
        start(() -> new Session.Listener()
        {
            @Override
            public Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
            {
                return new Stream.Listener()
                {
                    @Override
                    public void onNewStream(Stream stream, Frame.WithStreamId frame)
                    {
                        stream.data(true, RetainableByteBuffer.wrap(ByteBuffer.allocate(length)), Promise.Invocable.noop());
                    }
                };
            }
        });

        CountDownLatch clientDataLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onPrepare(Session session, TransportParameters transportParameters)
            {
                transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL, Math.max(128, 2L * length));
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);
        long streamId = clientSession.newStreamId(true);
        AtomicLong received = new AtomicLong();
        Stream clientStream = clientSession.newStream(streamId, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                while (true)
                {
                    Content.Chunk chunk = stream.read();
                    if (chunk == null)
                    {
                        stream.demand();
                        return;
                    }
                    received.addAndGet(chunk.remaining());
                    chunk.release();
                    if (chunk.isLast())
                    {
                        clientDataLatch.countDown();
                        return;
                    }
                }
            }
        });
        clientStream.data(true, RetainableByteBuffer.EMPTY, Promise.Invocable.noop());
        clientStream.demand();

        assertTrue(clientDataLatch.await(15, SECONDS));
        assertEquals(length, received.get());
    }

    @Test
    public void testStreamFrameAfterStreamTerminationIsDiscarded() throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        start(() -> new Session.Listener()
        {
            @Override
            public Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
            {
                return new Stream.Listener()
                {
                    @Override
                    public void onNewStream(Stream stream, Frame.WithStreamId frame)
                    {
                        // Only demand for the first stream.
                        // If a second stream is created, it should fail the test.
                        if (serverStreamRef.compareAndSet(null, stream))
                            stream.demand();
                    }

                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        while (true)
                        {
                            Content.Chunk chunk = stream.read();
                            if (chunk == null)
                            {
                                stream.demand();
                                return;
                            }
                            chunk.release();
                            if (chunk.isLast())
                                break;
                        }
                        stream.data(true, RetainableByteBuffer.EMPTY, Promise.Invocable.noop());
                    }
                };
            }
        });

        Promise.Completable<Session> promise = new Promise.Completable<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {}, promise);
        Session clientSession = promise.get(5, SECONDS);

        CountDownLatch clientDataLatch = new CountDownLatch(1);
        long streamId = clientSession.newStreamId(true);
        Stream clientStream = clientSession.newStream(streamId, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                while (true)
                {
                    Content.Chunk chunk = stream.read();
                    if (chunk == null)
                    {
                        stream.demand();
                        return;
                    }
                    chunk.release();
                    if (chunk.isLast())
                        break;
                }
                clientDataLatch.countDown();
            }
        });

        clientStream.data(true, RetainableByteBuffer.EMPTY, Promise.Invocable.noop());
        clientStream.demand();

        assertTrue(clientDataLatch.await(5, SECONDS));
        Stream serverStream = serverStreamRef.get();
        Session serverSession = serverStream.getSession();
        await().atMost(5, SECONDS).untilAsserted(() -> assertThat(serverSession.getStreams(), empty()));
        await().atMost(5, SECONDS).untilAsserted(() -> assertThat(clientSession.getStreams(), empty()));

        Thread.sleep(500);

        // Send another StreamFrame, simulating that the server receives a re-transmission that's not necessary.
        ((QuicSession)clientSession).data((QuicStream)clientStream, new StreamFrame(clientStream.getId(), RetainableByteBuffer.EMPTY, false), Promise.Invocable.noop());
        // Wait for the frame to reach the server, but it should be discarded.
        await().during(1, SECONDS).atMost(5, SECONDS).untilAsserted(() -> assertThat(serverSession.getStreams(), empty()));

        // Send a StreamFrame from the server, simulating a re-transmission.
        ((QuicSession)serverSession).data((QuicStream)serverStream, new StreamFrame(clientStream.getId(), RetainableByteBuffer.EMPTY, false), Promise.Invocable.noop());
        // Wait for the frame to reach the client, but it should be discarded.
        await().during(1, SECONDS).atMost(5, SECONDS).untilAsserted(() -> assertThat(clientSession.getStreams(), empty()));
    }
}
