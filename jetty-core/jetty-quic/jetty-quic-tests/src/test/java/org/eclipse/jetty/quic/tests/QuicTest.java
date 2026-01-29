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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;
import static org.hamcrest.MatcherAssert.assertThat;
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
        ).get(5, TimeUnit.SECONDS);

        assertNotNull(session);

        List<String> expectedEvents = List.of("prepare", "transportParameters", "open");
        await().atMost(5, TimeUnit.SECONDS).until(serverEvents::size, equalTo(3));
        assertThat(serverEvents, equalTo(expectedEvents));
        assertThat(clientEvents, equalTo(expectedEvents));
    }

    @Test
    public void testStreamEcho() throws Exception
    {
        start(() -> new Session.Listener()
        {
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
                        stream.data(chunk.isLast(), List.of(chunk.getByteBuffer()), Promise.Invocable.from(NON_BLOCKING, (s, x) ->
                        {
                            if (x == null)
                                s.demand();
                        }));
                    }
                };
            }
        });

        Session session = Promise.Completable.<Session>with(p ->
            client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {}, p)
        ).get(5, TimeUnit.SECONDS);

        CountDownLatch dataLatch = new CountDownLatch(1);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(client.getClientConnector().getByteBufferPool(), false, -1, 0, 0);
        long streamId = session.newStreamId(true);
        Stream stream = session.newStream(streamId, new Stream.Listener()
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

        byte[] bytes = "Hello QUIC".getBytes();
        stream.data(true, List.of(ByteBuffer.wrap(bytes)), Promise.Invocable.noop());

        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
        assertEquals(ByteBuffer.wrap(bytes), accumulator.getByteBuffer());
    }
}
