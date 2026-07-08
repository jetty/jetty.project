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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.PingFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.common.CongestionController;
import org.eclipse.jetty.quic.common.NewRenoCongestionControllerFactory;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.RTTData;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CongestionTest extends AbstractTest
{
    private static final Logger LOG = LoggerFactory.getLogger(CongestionTest.class);

    /// Tests the following case:
    /// 1. Send packet P1
    /// 1. Congestion window goes to zero.
    /// 1. Packet P2 cannot be sent because of congestion.
    /// 1. Acks for P1 and P2 are lost, they would enlarge the congestion window.
    /// 1. Sends are stalled.
    /// 1. Probe timeout fires, sends a probe packet that must bypass the congestion window.
    /// 1. The ack for the probe packet arrives, and sending resumes.
    @Test
    public void testCongestionThenPacketLossResolvedByProbe() throws Exception
    {
        // Send data that spans 3+ UDP datagrams.
        byte[] data = new byte[4 * 1024];
        ThreadLocalRandom.current().nextBytes(data);

        // -1: initial, non-congested; 0: congested; 1: after congestion, non-congested.
        AtomicInteger congested = new AtomicInteger(-1);
        prepareServer(() -> new Session.Listener()
        {
            @Override
            public Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
            {
                QuicSession quicSession = (QuicSession)session;
                quicSession.setPacketListener(new QuicSession.PacketListener.Wrapper(quicSession.getPacketListener())
                {
                    @Override
                    public void onIncomingPacket(Session session, Packet packet)
                    {
                        // Packets are lost when congested.
                        if (congested.get() == 0)
                        {
                            LOG.debug("dropped {}", packet);
                            return;
                        }
                        super.onIncomingPacket(session, packet);
                    }
                });
                return new Stream.Listener()
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
                        stream.data(true, RetainableByteBuffer.wrap(ByteBuffer.wrap(data)), Callback.NOOP);
                    }
                };
            }
        });
        serverQuicConfig.setCongestionControllerFactory(new NewRenoCongestionControllerFactory()
        {
            @Override
            public CongestionController newCongestionController()
            {
                return new NewRenoCongestionController(this)
                {
                    @Override
                    public void onPacketSent(Packet.WithFrames packet, long length, boolean dataStalled, RTTData rttData)
                    {
                        LOG.debug("sending {}", packet);

                        if (packet.frames().stream().anyMatch(f -> f instanceof PingFrame))
                        {
                            LOG.debug("sending probe, disabling congestion");
                            congested.set(1);
                        }
                        else if (packet.frames().stream().anyMatch(f -> f instanceof StreamFrame) && congested.get() < 0)
                        {
                            LOG.debug("enabling congestion");
                            congested.set(0);
                        }
                        super.onPacketSent(packet, length, dataStalled, rttData);
                    }

                    @Override
                    public long getAvailableWindow()
                    {
                        if (congested.get() == 0)
                            return 0;
                        return super.getAvailableWindow();
                    }
                };
            }
        });
        server.start();

        prepareClient();
        quicClient.start();

        Promise.Completable<Session> promise = new Promise.Completable<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener() {}, promise);
        Session clientSession = promise.get(5, SECONDS);

        ByteBuffer received = ByteBuffer.allocate(data.length);
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
                    received.put(chunk.getByteBuffer());
                    chunk.release();
                    if (chunk.isLast())
                        break;
                }
                clientDataLatch.countDown();
            }
        });
        clientStream.data(true, RetainableByteBuffer.EMPTY, Callback.NOOP);
        clientStream.demand();

        assertTrue(clientDataLatch.await(5, SECONDS));
        assertThat(ByteBuffer.wrap(data), equalTo(received.flip()));
    }
}
