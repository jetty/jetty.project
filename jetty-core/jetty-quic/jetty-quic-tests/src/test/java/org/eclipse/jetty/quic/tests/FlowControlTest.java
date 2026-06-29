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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStream;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FlowControlTest extends AbstractTest
{
    @Test
    public void testSessionFlowControlStall() throws Exception
    {
        int maxData = 512;
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverDataBlockedLatch = new CountDownLatch(1);
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        start(() -> new Session.Listener()
        {
            @Override
            public void onPrepare(Session session, TransportParameters transportParameters)
            {
                // Limit the session, but not the streams.
                transportParameters.put(TransportParameters.Ids.INITIAL_MAX_DATA, (long)maxData);
                transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE, maxData * 10L);
            }

            @Override
            public Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
            {
                return new Stream.Listener()
                {
                    @Override
                    public void onNewStream(Stream stream, Frame.WithStreamId frame)
                    {
                        serverStreamRef.set(stream);
                        // Do not demand yet.
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
                            {
                                stream.reset(ErrorCode.NO_ERROR.code(), Callback.from(NON_BLOCKING, serverDataLatch::countDown));
                                return;
                            }
                        }
                    }

                    @Override
                    public void onDataBlocked(Stream stream, StreamDataBlockedFrame frame)
                    {
                        // TODO
                        Stream.Listener.super.onDataBlocked(stream, frame);
                    }
                };
            }

            @Override
            public void onDataBlocked(Session session, DataBlockedFrame frame)
            {
                serverDataBlockedLatch.countDown();
            }
        });

        CountDownLatch clientMaxDataLatch = new CountDownLatch(1);
        CompletableFuture<Session> sessionFuture = new  CompletableFuture<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onMaxData(Session session, MaxDataFrame frame)
            {
                clientMaxDataLatch.countDown();
            }
        }, Promise.Invocable.toPromise(sessionFuture));
        QuicSession clientSession = (QuicSession)sessionFuture.get(5, SECONDS);

        long streamId = clientSession.newStreamId(true);
        QuicStream clientStream = (QuicStream)clientSession.newStream(streamId, new Stream.Listener() {});

        // Try to send more than allowed by the server.
        int excessData = 1;
        long totalData = maxData + excessData;
        ByteBuffer byteBuffer = ByteBuffer.allocate(maxData + excessData);
        CompletableFuture<Stream> streamFuture = new  CompletableFuture<>();
        clientStream.data(true, RetainableByteBuffer.wrap(byteBuffer), Callback.from(streamFuture));
        await().during(1, SECONDS).atMost(5, SECONDS).until(() -> !streamFuture.isDone());

        // The client must send a DATA_BLOCKED frame to the server.
        assertTrue(serverDataBlockedLatch.await(5, SECONDS));
        assertEquals(maxData, clientSession.getSendMaxOffset());
        // The server did not consume the data, so it must not send a MAX_DATA frame.
        assertFalse(clientMaxDataLatch.await(1, SECONDS));
        // The client must have sent only part of the data.
        assertEquals(excessData, byteBuffer.remaining());

        // Read from the server to consume the data.
        // The server must send a MAX_DATA and the client finish sending.
        QuicStream serverStream = (QuicStream)serverStreamRef.get();
        serverStream.demand();

        assertTrue(clientMaxDataLatch.await(5, SECONDS));
        streamFuture.get(5, SECONDS);
        assertTrue(serverDataLatch.await(5, SECONDS));

        QuicSession serverSession = serverStream.getSession();
        assertEquals(totalData, clientSession.getSentOffset());
        assertEquals(2 * maxData, clientSession.getSendMaxOffset());
        assertEquals(totalData, clientStream.getSentOffset());
        assertEquals(totalData, serverSession.getRecvOffset());
        assertEquals(2 * maxData, serverSession.getRecvMaxOffset());
        assertEquals(totalData, serverStream.getRecvOffset());
    }

    @Test
    public void testSessionFlowControlStallWithMultipleStreams() throws Exception
    {
        int maxData = 500;
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        AtomicInteger serverDataBlockedCounter = new AtomicInteger();
        CountDownLatch serverDataLatch = new CountDownLatch(2);
        start(() -> new Session.Listener()
        {
            @Override
            public void onPrepare(Session session, TransportParameters transportParameters)
            {
                serverSessionRef.set(session);
                // Limit the session, but not the streams.
                transportParameters.put(TransportParameters.Ids.INITIAL_MAX_DATA, (long)maxData);
                transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE, maxData * 10L);
                transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_BIDIRECTIONAL, 2L);
            }

            @Override
            public Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
            {
                return new Stream.Listener()
                {
                    @Override
                    public void onNewStream(Stream stream, Frame.WithStreamId frame)
                    {
                        // Do not demand yet.
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
                            {
                                stream.data(true, RetainableByteBuffer.EMPTY, Callback.from(NON_BLOCKING, serverDataLatch::countDown));
                                return;
                            }
                        }
                    }
                };
            }

            @Override
            public void onDataBlocked(Session session, DataBlockedFrame frame)
            {
                serverDataBlockedCounter.incrementAndGet();
            }
        });

        CountDownLatch clientMaxDataLatch = new CountDownLatch(1);
        CompletableFuture<Session> sessionFuture = new  CompletableFuture<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onMaxData(Session session, MaxDataFrame frame)
            {
                clientMaxDataLatch.countDown();
            }
        }, Promise.Invocable.toPromise(sessionFuture));
        QuicSession clientSession = (QuicSession)sessionFuture.get(5, SECONDS);

        long streamId1 = clientSession.newStreamId(true);
        QuicStream clientStream1 = (QuicStream)clientSession.newStream(streamId1, new Stream.Listener() {});
        long streamId2 = clientSession.newStreamId(true);
        QuicStream clientStream2 = (QuicStream)clientSession.newStream(streamId2, new Stream.Listener() {});

        // Send data with both streams until the session stalls.
        int chunk1 = 300;
        ByteBuffer byteBuffer1 = ByteBuffer.allocate(chunk1);
        CompletableFuture<Stream> streamFuture1 = new  CompletableFuture<>();
        clientStream1.data(false, RetainableByteBuffer.wrap(byteBuffer1), Callback.from(streamFuture1));
        streamFuture1.get(5, SECONDS);

        int chunk2 = maxData - chunk1;
        ByteBuffer byteBuffer2 = ByteBuffer.allocate(chunk2);
        CompletableFuture<Stream> streamFuture2 = new  CompletableFuture<>();
        clientStream2.data(false, RetainableByteBuffer.wrap(byteBuffer2), Callback.from(streamFuture2));
        streamFuture2.get(5, SECONDS);

        // Verify that the session is flow control stalled.
        QuicSession serverSession = (QuicSession)await().atMost(5, SECONDS).until(serverSessionRef::get, notNullValue());
        await().atMost(5, SECONDS).until(serverSession::getRecvOffset, equalTo(serverSession.getRecvMaxOffset()));

        // Trying to send more results in stalls.
        int excess1 = 30;
        ByteBuffer byteBuffer3 = ByteBuffer.allocate(excess1);
        CompletableFuture<Stream> streamFuture3 = new  CompletableFuture<>();
        clientStream1.data(true, RetainableByteBuffer.wrap(byteBuffer3), Callback.from(streamFuture3));
        await().during(1, SECONDS).atMost(5, SECONDS).until(() -> !streamFuture3.isDone());

        int excess2 = 20;
        ByteBuffer byteBuffer4 = ByteBuffer.allocate(excess2);
        CompletableFuture<Stream> streamFuture4 = new  CompletableFuture<>();
        clientStream2.data(true, RetainableByteBuffer.wrap(byteBuffer4), Callback.from(streamFuture4));
        await().during(1, SECONDS).atMost(5, SECONDS).until(() -> !streamFuture4.isDone());

        // The client must send only 1 DATA_BLOCKED frame to the server.
        await().during(1, SECONDS).atMost(5, SECONDS).until(serverDataBlockedCounter::get, equalTo(1));
        assertEquals(maxData, clientSession.getSendMaxOffset());
        // The server did not consume the data, so it must not send a MAX_DATA frame.
        assertFalse(clientMaxDataLatch.await(1, SECONDS));

        // Read from the server to consume the data.
        // The server must send a MAX_DATA and the client finish sending.
        assertThat(serverSession.getStreams(), hasSize(2));
        serverSession.getStreams().forEach(Stream::demand);

        assertTrue(clientMaxDataLatch.await(5, SECONDS));
        streamFuture3.get(5, SECONDS);
        streamFuture4.get(5, SECONDS);
        // All chunks and excesses have been read by the server.
        assertTrue(serverDataLatch.await(5, SECONDS));

        int totalData = maxData + excess1 + excess2;
        assertEquals(totalData, clientSession.getSentOffset());
        assertEquals(chunk1 + excess1, clientStream1.getSentOffset());
        assertEquals(chunk2 + excess2, clientStream2.getSentOffset());

        assertEquals(totalData, serverSession.getRecvOffset());
        long newMaxData = maxData + excess1 + maxData;
        await().atMost(5, SECONDS).until(clientSession::getSendMaxOffset, equalTo(newMaxData));
        assertEquals(newMaxData, serverSession.getRecvMaxOffset());
    }

    @Test
    public void testStreamFlowControlStall() throws Exception
    {
        int maxData = 512;
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        AtomicInteger serverDataBlockedCounter = new AtomicInteger();
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        start(() -> new Session.Listener()
        {
            @Override
            public void onPrepare(Session session, TransportParameters transportParameters)
            {
                // Limit the streams, but not the session.
                transportParameters.put(TransportParameters.Ids.INITIAL_MAX_DATA, maxData * 10L);
                transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE, (long)maxData);
            }

            @Override
            public Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
            {
                return new Stream.Listener()
                {
                    @Override
                    public void onNewStream(Stream stream, Frame.WithStreamId frame)
                    {
                        serverStreamRef.set(stream);
                        // Do not demand yet.
                    }

                    @Override
                    public void onDataBlocked(Stream stream, StreamDataBlockedFrame frame)
                    {
                        serverDataBlockedCounter.incrementAndGet();
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
                            {
                                stream.reset(ErrorCode.NO_ERROR.code(), Callback.from(NON_BLOCKING, serverDataLatch::countDown));
                                return;
                            }
                        }
                    }
                };
            }
        });

        CompletableFuture<Session> sessionFuture = new  CompletableFuture<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener() {}, Promise.Invocable.toPromise(sessionFuture));
        QuicSession clientSession = (QuicSession)sessionFuture.get(5, SECONDS);

        long streamId = clientSession.newStreamId(true);
        CountDownLatch clientMaxDataLatch = new CountDownLatch(1);
        QuicStream clientStream = (QuicStream)clientSession.newStream(streamId, new Stream.Listener()
        {
            @Override
            public void onMaxData(Stream stream, StreamMaxDataFrame frame)
            {
                clientMaxDataLatch.countDown();
            }
        });

        // Try to send more than allowed by the server.
        int excessData = 1;
        long totalData = maxData + excessData;
        ByteBuffer byteBuffer = ByteBuffer.allocate(maxData + excessData);
        CompletableFuture<Stream> streamFuture = new  CompletableFuture<>();
        clientStream.data(true, RetainableByteBuffer.wrap(byteBuffer), Callback.from(streamFuture));
        await().during(1, SECONDS).atMost(5, SECONDS).until(() -> !streamFuture.isDone());

        // The client must send a STREAM_DATA_BLOCKED frame to the server.
        await().atMost(5, SECONDS).until(serverDataBlockedCounter::get, equalTo(1));
        assertEquals(maxData, clientStream.getSendMaxOffset());
        // The server did not consume the data, so it must not send a MAX_DATA frame.
        assertFalse(clientMaxDataLatch.await(1, SECONDS));
        // The client must have sent only part of the data.
        assertEquals(excessData, byteBuffer.remaining());

        // Trying to flush more must not result in another STREAM_DATA_BLOCKED.
        clientSession.ping(Callback.NOOP);
        await().during(1, SECONDS).atMost(5, SECONDS).until(serverDataBlockedCounter::get, equalTo(1));

        // Read from the server to consume the data.
        // The server must send a STREAM_MAX_DATA and the client finish sending.
        QuicStream serverStream = (QuicStream)serverStreamRef.get();
        serverStream.demand();

        assertTrue(clientMaxDataLatch.await(5, SECONDS));
        streamFuture.get(5, SECONDS);
        assertTrue(serverDataLatch.await(5, SECONDS));

        QuicSession serverSession = serverStream.getSession();
        assertEquals(totalData, clientSession.getSentOffset());
        assertEquals(totalData, clientStream.getSentOffset());
        assertEquals(2 * maxData, clientStream.getSendMaxOffset());
        assertEquals(totalData, serverSession.getRecvOffset());
        assertEquals(totalData, serverStream.getRecvOffset());
        assertEquals(2 * maxData, serverStream.getRecvMaxOffset());
    }
}
