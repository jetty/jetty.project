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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.server.internal.ServerQuicConnection;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SessionIdleTimeoutTest extends AbstractQuicTest
{
    @Test
    public void testClientOnlyIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        prepareServer(() -> new Session.Listener()
        {
            @Override
            public void onOpen(Session session)
            {
                serverSessionRef.set(session);
                // Reset the idle timeout on the server
                // so that only the client idle times out.
                ((QuicSession)session).setIdleTimeout(0);
            }

            @Override
            public void onClose(Session session, ConnectionCloseFrame frame)
            {
                serverCloseLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session)
            {
                serverDisconnectLatch.countDown();
            }
        });
        connector.setIdleTimeout(0);
        server.start();
        prepareClient();
        client.getClientConnector().setIdleTimeout(Duration.ofMillis(idleTimeout));
        client.start();

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onPrepare(Session session, TransportParameters parameters)
            {
                parameters.put(TransportParameters.Ids.MAX_IDLE_TIMEOUT, idleTimeout);
            }

            @Override
            public void onFailure(Session session, Throwable failure)
            {
                if (failure instanceof TimeoutException)
                    clientFailureLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session)
            {
                clientDisconnectLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);
        ((QuicSession)clientSession).setKeepAliveEnabled(false);

        // RFC-9000[10.1] says the connection should be silently closed,
        // but we actually send a CLOSE_CONNECTION to the remote peer,
        // so that it can detect earlier that the connection is broken.
        assertTrue(clientFailureLatch.await(2 * idleTimeout, MILLISECONDS));
        assertTrue(clientDisconnectLatch.await(1, SECONDS));
        assertTrue(serverCloseLatch.await(1, SECONDS));
        assertTrue(serverDisconnectLatch.await(1, SECONDS));

        // After a 3xPTO, the client DatagramChannel should be closed.
        await().atMost(5, SECONDS).until(((QuicSession)clientSession).getEndPoint()::isOpen, is(false));

        // On the server, the session should be gone.
        QuicSession serverSession = (QuicSession)serverSessionRef.get();
        await().atMost(5, SECONDS).until(((ServerQuicConnection)serverSession.getQuicConnection())::getSessions, empty());
    }

    @Test
    public void testServerOnlyIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        prepareServer(() -> new Session.Listener()
        {
            @Override
            public void onPrepare(Session session, TransportParameters parameters)
            {
                parameters.put(TransportParameters.Ids.MAX_IDLE_TIMEOUT, idleTimeout);
            }

            @Override
            public void onOpen(Session session)
            {
                serverSessionRef.set(session);
                ((QuicSession)session).setKeepAliveEnabled(false);
            }

            @Override
            public void onFailure(Session session, Throwable failure)
            {
                if (failure instanceof TimeoutException)
                    serverFailureLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session)
            {
                serverDisconnectLatch.countDown();
            }
        });
        connector.setIdleTimeout(idleTimeout);
        server.start();
        prepareClient();
        client.getClientConnector().setIdleTimeout(Duration.ZERO);
        client.start();

        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onClose(Session session, ConnectionCloseFrame frame)
            {
                clientCloseLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session)
            {
                clientDisconnectLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);
        // Reset the idle timeout on the client
        // so that only the server idle times out.
        ((QuicSession)clientSession).setIdleTimeout(0);

        // RFC-9000[10.1] says the connection should be silently closed,
        // but we actually send a CLOSE_CONNECTION to the remote peer,
        // so that it can detect earlier that the connection is broken.
        assertTrue(serverFailureLatch.await(2 * idleTimeout, MILLISECONDS));
        assertTrue(serverDisconnectLatch.await(1, SECONDS));
        assertTrue(clientCloseLatch.await(1, SECONDS));
        assertTrue(clientDisconnectLatch.await(1, SECONDS));

        // After a while, the client DatagramChannel should be closed.
        await().atMost(5, SECONDS).until(((QuicSession)clientSession).getEndPoint()::isOpen, is(false));

        // On the server, the session should be gone.
        QuicSession serverSession = (QuicSession)serverSessionRef.get();
        await().atMost(5, SECONDS).until(((ServerQuicConnection)serverSession.getQuicConnection())::getSessions, empty());
    }

    @Test
    public void testClientAndServerMinimumIdleTimeout() throws Exception
    {
        long serverIdleTimeout = 1000;
        long clientIdleTimeout = 3 * serverIdleTimeout;

        prepareServer(() -> new Session.Listener()
        {
            @Override
            public void onOpen(Session session)
            {
                // Disable idle timeout on the server,
                // we are only interested that the client
                // uses the minimum idle timeout.
                ((QuicSession)session).setIdleTimeout(0);
                ((QuicSession)session).setKeepAliveEnabled(false);
            }
        });
        connector.setIdleTimeout(serverIdleTimeout);
        server.start();
        prepareClient();
        client.getClientConnector().setIdleTimeout(Duration.ofMillis(clientIdleTimeout));
        client.start();

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onFailure(Session session, Throwable failure)
            {
                if (failure instanceof TimeoutException)
                    clientFailureLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);
        ((QuicSession)clientSession).setKeepAliveEnabled(false);

        // The client must idle timeout at the server's idle timeout.
        assertTrue(clientFailureLatch.await(2 * serverIdleTimeout, MILLISECONDS));
    }

    @Test
    public void testIdleTimeoutWithKeepAliveDoesNotFire() throws Exception
    {
        long clientIdleTimeout = 1000;

        start(() -> new Session.Listener()
        {
            @Override
            public void onOpen(Session session)
            {
                ((QuicSession)session).setIdleTimeout(0);
                ((QuicSession)session).setKeepAliveEnabled(false);
            }
        });
        client.getClientConnector().setIdleTimeout(Duration.ofMillis(clientIdleTimeout));

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onFailure(Session session, Throwable failure)
            {
                clientFailureLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);
        ((QuicSession)clientSession).setKeepAliveEnabled(true);

        assertFalse(clientFailureLatch.await(2 * clientIdleTimeout, MILLISECONDS));
    }

    @Test
    public void testIdleTimeoutWithKeepAliveRemotePeerDoesNotRespond() throws Exception
    {
        long clientIdleTimeout = 1000;

        start(() -> new Session.Listener()
        {
            @Override
            public void onOpen(Session session)
            {
                ((QuicSession)session).setIdleTimeout(0);
                ((QuicSession)session).setKeepAliveEnabled(false);
            }
        });
        client.getClientConnector().setIdleTimeout(Duration.ofMillis(clientIdleTimeout));

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onFailure(Session session, Throwable failure)
            {
                clientFailureLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);
        QuicSession clientQuicSession = (QuicSession)clientSession;
        clientQuicSession.setPacketListener(new Packet.Listener.Wrapper(clientQuicSession.getPacketListener())
        {
            @Override
            public void onIncomingPacket(Session session, Packet packet)
            {
                // Drop packets arriving from the server,
                // they should be acks for keepalive probes.
                if (packet instanceof Packet.WithFrames pwf)
                {
                    if (pwf.frames().stream().allMatch(f -> f instanceof AckFrame))
                        return;
                }
                super.onIncomingPacket(session, packet);
            }
        });

        assertTrue(clientFailureLatch.await(2 * clientIdleTimeout, MILLISECONDS));
    }
}
