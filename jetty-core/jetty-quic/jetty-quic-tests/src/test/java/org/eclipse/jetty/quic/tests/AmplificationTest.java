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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.client.internal.DefaultTokenStore;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.util.Promise;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AmplificationTest extends AbstractTest
{
    @Test
    public void testAmplificationLimit() throws Exception
    {
        AtomicReference<QuicSession> serverSessionRef = new AtomicReference<>();
        // -1 don't drop; 0 drop next; 1 drop current.
        AtomicInteger packetDropper = new AtomicInteger(-1);
        start(() -> new Session.Listener()
        {
            @Override
            public void onCreated(Session session)
            {
                QuicSession quicSession = (QuicSession)session;
                serverSessionRef.set(quicSession);
                quicSession.setPacketListener(new QuicSession.PacketListener.Wrapper(quicSession.getPacketListener())
                {
                    @Override
                    public void onIncomingPacket(Session session, Packet packet)
                    {
                        int drop = packetDropper.get();
                        if (drop > 0)
                            return;
                        if (drop == 0)
                            packetDropper.set(1);
                        super.onIncomingPacket(session, packet);
                    }
                });
            }
        });

        // Open a first session to make the server send a token to the client.
        CompletableFuture<Session> future1 = new CompletableFuture<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener() {}, Promise.Invocable.toPromise(future1));
        QuicSession clientSession1 = (QuicSession)future1.get(5, TimeUnit.SECONDS);
        assertThat(clientSession1.getBytesReceived(), greaterThan(3 * 1200L));

        // Wait for the token to be stored.
        await().atMost(5, TimeUnit.SECONDS).until(() -> ((DefaultTokenStore)quicClient.getTokenStore()).size(), Matchers.equalTo(1));

        // For the second session, drop all packets after
        // the first, to simulate the amplification attack.
        packetDropper.set(0);

        // Open the second session, that will use the token.
        // However, the server won't receive packets after the first,
        // and won't amplify, resulting in the session creation to hang.
        CompletableFuture<Session> future2 = new CompletableFuture<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener() {}, Promise.Invocable.toPromise(future2));
        assertThrows(TimeoutException.class, () -> future2.get(1, TimeUnit.SECONDS));

        // Allow close frames to arrive to the server.
        packetDropper.set(-1);

        QuicSession serverSession2 = serverSessionRef.get();
        assertFalse(serverSession2.isRemoteAddressValidated());
        long serverBytesSent = serverSession2.getBytesSent();
        assertThat(serverBytesSent, greaterThan(0L));
        assertThat(serverBytesSent, lessThanOrEqualTo(3 * serverSession2.getBytesReceived()));
    }
}
