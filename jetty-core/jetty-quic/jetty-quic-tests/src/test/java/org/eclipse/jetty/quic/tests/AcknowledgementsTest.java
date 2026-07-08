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

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.packets.OneRTTPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AcknowledgementsTest extends AbstractTest
{
    @Test
    public void testAckForUnsentPacketNumber() throws Exception
    {
        start(() -> new Session.Listener()
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
                        if (EncryptionLevel.from(packet) == EncryptionLevel.ONE_RTT)
                        {
                            if (packet instanceof OneRTTPacket onePkt && onePkt.frames().stream().anyMatch(f -> f instanceof AckFrame))
                            {
                                List<Frame> newFrames = new ArrayList<>(onePkt.frames());
                                // Simulate the receipt of an ack frame for a packet that has not been sent.
                                newFrames.add(new AckFrame(1024, 0, 0, List.of()));
                                packet = new OneRTTPacket(onePkt.packetNumber(), onePkt.destinationConnectionId(), onePkt.spin(), onePkt.keyPhase(), newFrames);
                            }
                            super.onIncomingPacket(session, packet);
                        }
                    }
                });
                return new Stream.Listener()
                {
                    @Override
                    public void onNewStream(Stream stream, Frame.WithStreamId frame)
                    {
                        // Send data so that the client acknowledges it.
                        stream.data(true, RetainableByteBuffer.EMPTY, Callback.NOOP);
                    }
                };
            }
        });

        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onClose(Session session, ConnectionCloseFrame frame)
            {
                assertEquals(ErrorCode.PROTOCOL_VIOLATION_ERROR.code(), frame.errorCode());
                clientCloseLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);

        long streamId = clientSession.newStreamId(true);
        Stream clientStream = clientSession.newStream(streamId, new Stream.Listener() {});
        // Open a new stream, so the server can send data.
        clientStream.reset(0x00, Callback.NOOP);

        assertTrue(clientCloseLatch.await(5, SECONDS));
    }

    @Test
    public void testAckForSkippedPacketNumber() throws Exception
    {
        start(() -> new Session.Listener()
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
                        if (EncryptionLevel.from(packet) == EncryptionLevel.ONE_RTT)
                        {
                            if (packet instanceof OneRTTPacket onePkt && onePkt.frames().stream().anyMatch(f -> f instanceof AckFrame))
                            {
                                List<Long> skipped = quicSession.getPacketNumbers().skippedPacketNumbers(EncryptionLevel.ONE_RTT);
                                if (!skipped.isEmpty())
                                {
                                    long skippedPacketNumber = skipped.getFirst();
                                    List<Frame> newFrames = new ArrayList<>(onePkt.frames());
                                    // Simulate the receipt of an ack frame for a packet that has been skipped.
                                    newFrames.add(new AckFrame(skippedPacketNumber, 0, 0, List.of()));
                                    packet = new OneRTTPacket(onePkt.packetNumber(), onePkt.destinationConnectionId(), onePkt.spin(), onePkt.keyPhase(), newFrames);
                                }
                            }
                            super.onIncomingPacket(session, packet);
                        }
                    }
                });
                return new Stream.Listener()
                {
                    @Override
                    public void onNewStream(Stream stream, Frame.WithStreamId frame)
                    {
                        // Send data so that the client acknowledges it.
                        // Must be enough to trigger packet number skipping, which is
                        // about one initial congestion window (about 10 * 1200 bytes).
                        stream.data(true, RetainableByteBuffer.wrap(ByteBuffer.allocate(64 * 1024)), Callback.NOOP);
                    }
                };
            }
        });

        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onClose(Session session, ConnectionCloseFrame frame)
            {
                assertEquals(ErrorCode.PROTOCOL_VIOLATION_ERROR.code(), frame.errorCode());
                clientCloseLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);

        long streamId = clientSession.newStreamId(true);
        Stream clientStream = clientSession.newStream(streamId, new Stream.Listener() {});
        // Open a new stream, so the server can send data.
        clientStream.reset(0x00, Callback.NOOP);

        assertTrue(clientCloseLatch.await(5, SECONDS));
    }
}
