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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MaxStreamsTest extends AbstractQuicTest
{
    @Test
    public void testMaxStreams() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        start(() -> new Session.Listener()
        {
            @Override
            public void onPrepare(Session session, TransportParameters transportParameters)
            {
                serverSessionRef.set(session);
                transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_BIDIRECTIONAL, 1L);
                transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_UNIDIRECTIONAL, 0L);
            }
        });

        CountDownLatch maxStreamsLatch = new CountDownLatch(1);
        CompletableFuture<Session> future = new CompletableFuture<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onMaxStreams(Session session, MaxStreamsFrame frame)
            {
                maxStreamsLatch.countDown();
            }
        }, Promise.Invocable.toPromise(future));
        Session clientSession = future.get(5, SECONDS);

        // Cannot open unidirectional streams.
        long uniStreamId = clientSession.newStreamId(false);
        QuicException failure = assertThrows(QuicException.class, () -> clientSession.newStream(uniStreamId, Stream.Listener.DEFAULT));
        assertSame(ErrorCode.STREAM_LIMIT_ERROR, failure.getErrorCode());

        // Can only open 1 bidirectional stream.
        long streamId1 = clientSession.newStreamId(true);
        Stream stream1 = clientSession.newStream(streamId1, Stream.Listener.DEFAULT);
        long streamId2 = clientSession.newStreamId(true);
        failure = assertThrows(QuicException.class, () -> clientSession.newStream(streamId2, Stream.Listener.DEFAULT));
        assertSame(ErrorCode.STREAM_LIMIT_ERROR, failure.getErrorCode());

        stream1.disconnect(ErrorCode.NO_ERROR.code(), null, Promise.Invocable.noop());
        assertTrue(maxStreamsLatch.await(5, SECONDS));

        await().atMost(5, SECONDS).until(serverSessionRef.get()::getStreams, hasSize(0));

        // Wait for the stream to be closed also on the client-side.
        stream1.demand();
        await().atMost(5, SECONDS).until(stream1::isClosed);
        await().atMost(5, SECONDS).until(clientSession::getStreams, hasSize(0));

        // Verify that another stream can be opened.
        long streamId3 = clientSession.newStreamId(true);
        Stream stream3 = clientSession.newStream(streamId3, Stream.Listener.DEFAULT);
        stream3.disconnect(ErrorCode.NO_ERROR.code(), null, Promise.Invocable.noop());
    }
}
