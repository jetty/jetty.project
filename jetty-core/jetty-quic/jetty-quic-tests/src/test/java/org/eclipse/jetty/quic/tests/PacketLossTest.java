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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class PacketLossTest extends AbstractQuicTest
{
    @Test
    public void testFirstApplicationPacketLost() throws Exception
    {
        // Send content that spans 2 UDP datagrams.
        byte[] data = new byte[2 * 1024];
        ThreadLocalRandom.current().nextBytes(data);
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
                        stream.data(true, RetainableByteBuffer.wrap(ByteBuffer.wrap(data)), Promise.Invocable.noop());
                    }
                };
            }
        });

        Promise.Completable<Session> promise = new Promise.Completable<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {}, promise);
        QuicSession session = (QuicSession)promise.get(5, TimeUnit.SECONDS);

        session.setPacketListener(new Packet.Listener.Wrapper(session.getPacketListener())
        {
            private boolean dropped;

            @Override
            public void onIncomingPacket(Session session, Packet packet)
            {
                if (!dropped && EncryptionLevel.from(packet) == EncryptionLevel.ONE_RTT)
                {
                    if (packet instanceof Packet.WithFrames withFrames)
                    {
                        List<Frame> frames = withFrames.frames();
                        if (frames.size() == 1 && frames.getFirst() instanceof StreamFrame)
                        {
                            // Drop the first application packet.
                            dropped = true;
                            return;
                        }
                    }
                }
                super.onIncomingPacket(session, packet);
            }
        });

        ByteBuffer received = ByteBuffer.allocate(data.length);
        CompletableFuture<?> completable = new CompletableFuture<>();
        long streamId = session.newStreamId(true);
        Stream stream = session.newStream(streamId, new Stream.Listener()
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
                    if (Content.Chunk.isFailure(chunk))
                    {
                        completable.completeExceptionally(chunk.getFailure());
                        return;
                    }
                    received.put(chunk.getByteBuffer());
                    chunk.release();
                    if (chunk.isLast())
                    {
                        completable.complete(null);
                        return;
                    }
                }
            }
        });
        stream.data(true, RetainableByteBuffer.EMPTY, Promise.Invocable.noop());
        stream.demand();

        completable.get(5, TimeUnit.SECONDS);

        assertThat(ByteBuffer.wrap(data), equalTo(received.flip()));
    }
}
