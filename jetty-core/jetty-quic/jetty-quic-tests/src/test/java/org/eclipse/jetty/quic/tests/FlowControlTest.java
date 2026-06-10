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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.DefaultFlowController;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FlowControlTest extends AbstractQuicTest
{
    @Test
    public void testSessionFlowControlStall() throws Exception
    {
        int maxData = 512;
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverDataBlockedLatch = new CountDownLatch(1);
        prepareServer(() -> new Session.Listener()
        {
            @Override
            public void onPrepare(Session session, TransportParameters transportParameters)
            {
                // Limit the session, but not the streams.
                transportParameters.put(TransportParameters.Ids.INITIAL_MAX_DATA, (long)maxData);
                transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE, (long)maxData * 10);
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
                };
            }

            @Override
            public void onDataBlocked(Session session, DataBlockedFrame frame)
            {
                serverDataBlockedLatch.countDown();
            }
        });
        connector.getServerQuicConfiguration().setFlowControllerFactory(() -> new DefaultFlowController()
        {
        });
        server.start();

        prepareClient();
        client.start();

        CountDownLatch clientMaxDataLatch = new CountDownLatch(1);
        CompletableFuture<Session> sessionFuture = new  CompletableFuture<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onMaxData(Session session, MaxDataFrame frame)
            {
                clientMaxDataLatch.countDown();
            }
        }, Promise.Invocable.toPromise(sessionFuture));
        Session clientSession = sessionFuture.get(5, SECONDS);

        long streamId = clientSession.newStreamId(true);
        Stream stream = clientSession.newStream(streamId, new Stream.Listener() {});

        // Try to send more than allowed by the server.
        int excessData = maxData / 2;
        ByteBuffer byteBuffer = ByteBuffer.allocate(maxData + excessData);
        CompletableFuture<Stream> streamFuture = new  CompletableFuture<>();
        stream.data(true, RetainableByteBuffer.wrap(byteBuffer), Promise.Invocable.toPromise(streamFuture));

        // The client must send a DATA_BLOCKED frame to the server.
        assertTrue(serverDataBlockedLatch.await(555, SECONDS));
        // The server did not consume the data, so it must not send a MAX_DATA frame.
        assertFalse(clientMaxDataLatch.await(1, SECONDS));
        // The client must have sent only part of the data.
        assertEquals(excessData, byteBuffer.remaining());

        // Read from the server to consume the data.
        // The server must send a MAX_DATA and the client finish sending.
        serverStreamRef.get().demand();

        assertTrue(clientMaxDataLatch.await(5, SECONDS));
        streamFuture.get(5, SECONDS);

        // TODO: verify session/stream recv/send data on client and server.

    }
}
