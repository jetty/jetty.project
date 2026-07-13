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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.server.internal.ServerQuicConnection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SessionIdleTimeoutTest extends AbstractTest
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
            public void onDisconnect(Session session, ConnectionCloseFrame frame)
            {
                serverDisconnectLatch.countDown();
            }
        });
        serverConnector.setIdleTimeout(0);
        server.start();
        prepareClient();
        quicClient.getClientConnector().setIdleTimeout(Duration.ofMillis(idleTimeout));
        quicClient.start();

        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onPrepare(Session session, TransportParameters parameters)
            {
                parameters.put(TransportParameters.Ids.MAX_IDLE_TIMEOUT, idleTimeout);
            }

            @Override
            public void onDisconnect(Session session, ConnectionCloseFrame frame)
            {
                clientDisconnectLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);
        ((QuicSession)clientSession).setKeepAliveEnabled(false);

        // RFC-9000 #10.1 says the connection should be silently closed,
        // but we actually send a CLOSE_CONNECTION to the remote peer,
        // so that it can detect earlier that the connection is broken.
        assertTrue(clientDisconnectLatch.await(2 * idleTimeout, MILLISECONDS));
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
            public void onDisconnect(Session session, ConnectionCloseFrame frame)
            {
                serverDisconnectLatch.countDown();
            }
        });
        serverConnector.setIdleTimeout(idleTimeout);
        server.start();
        prepareClient();
        quicClient.getClientConnector().setIdleTimeout(Duration.ZERO);
        quicClient.start();

        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onClose(Session session, ConnectionCloseFrame frame)
            {
                clientCloseLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, ConnectionCloseFrame frame)
            {
                clientDisconnectLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);
        // Reset the idle timeout on the client
        // so that only the server idle times out.
        ((QuicSession)clientSession).setIdleTimeout(0);

        // RFC-9000 #10.1 says the connection should be silently closed,
        // but we actually send a CLOSE_CONNECTION to the remote peer,
        // so that it can detect earlier that the connection is broken.
        assertTrue(serverDisconnectLatch.await(2 * idleTimeout, MILLISECONDS));
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
        serverConnector.setIdleTimeout(serverIdleTimeout);
        server.start();
        prepareClient();
        quicClient.getClientConnector().setIdleTimeout(Duration.ofMillis(clientIdleTimeout));
        quicClient.start();

        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onDisconnect(Session session, ConnectionCloseFrame frame)
            {
                clientDisconnectLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);
        ((QuicSession)clientSession).setKeepAliveEnabled(false);

        // The client must idle timeout at the server's idle timeout.
        assertTrue(clientDisconnectLatch.await(2 * serverIdleTimeout, MILLISECONDS));
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
        quicClient.getClientConnector().setIdleTimeout(Duration.ofMillis(clientIdleTimeout));

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener()
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
        quicClient.getClientConnector().setIdleTimeout(Duration.ofMillis(clientIdleTimeout));

        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onDisconnect(Session session, ConnectionCloseFrame frame)
            {
                clientDisconnectLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);
        QuicSession clientQuicSession = (QuicSession)clientSession;
        clientQuicSession.setPacketListener(new QuicSession.PacketListener.Wrapper(clientQuicSession.getPacketListener())
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

        assertTrue(clientDisconnectLatch.await(2 * clientIdleTimeout, MILLISECONDS));
    }

    @Test
    public void testClientIdleTimeoutFailsAllStreams() throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        prepareServer(() -> new Session.Listener()
        {
            @Override
            public void onPrepare(Session session, TransportParameters transportParameters)
            {
                transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_BIDIRECTIONAL, 2L);
            }

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
        });
        serverConnector.setIdleTimeout(0);
        server.start();
        prepareClient();
        quicClient.getClientConnector().setIdleTimeout(Duration.ofMillis(idleTimeout));
        quicClient.start();

        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        quicClient.connect(new InetSocketAddress("localhost", serverConnector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onPrepare(Session session, TransportParameters parameters)
            {
                parameters.put(TransportParameters.Ids.MAX_IDLE_TIMEOUT, idleTimeout);
            }

            @Override
            public void onDisconnect(Session session, ConnectionCloseFrame frame)
            {
                clientDisconnectLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);
        ((QuicSession)clientSession).setKeepAliveEnabled(false);

        CountDownLatch clientStreamFailureLatch = new CountDownLatch(2);
        CountDownLatch clientStreamTerminatedLatch = new CountDownLatch(2);
        long streamId1 = clientSession.newStreamId(true);
        Stream clientStream1 = clientSession.newStream(streamId1, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Content.Chunk chunk = stream.read();
                assertTrue(Content.Chunk.isFailure(chunk), "unexpected chunk: " + chunk);
                clientStreamFailureLatch.countDown();
            }

            @Override
            public void onTerminated(Stream stream)
            {
                clientStreamTerminatedLatch.countDown();
            }
        });
        Consumer<Throwable> throwableConsumer = _ -> clientStream1.demand();
        clientStream1.data(true, RetainableByteBuffer.EMPTY, Callback.from(NON_BLOCKING, throwableConsumer));
        long streamId2 = clientSession.newStreamId(true);
        Stream clientStream2 = clientSession.newStream(streamId2, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Content.Chunk chunk = stream.read();
                assertTrue(Content.Chunk.isFailure(chunk), "unexpected chunk: " + chunk);
                clientStreamFailureLatch.countDown();
            }

            @Override
            public void onTerminated(Stream stream)
            {
                clientStreamTerminatedLatch.countDown();
            }
        });
        clientStream2.data(true, RetainableByteBuffer.EMPTY, Callback.from(NON_BLOCKING, _ -> clientStream2.demand()));

        Session serverSession = await().atMost(5, SECONDS).until(serverSessionRef::get, notNullValue());
        await().atMost(5, SECONDS).until(serverSession::getStreams, hasSize(2));

        assertTrue(clientStreamFailureLatch.await(2 * idleTimeout, MILLISECONDS));
        assertTrue(clientStreamTerminatedLatch.await(5, SECONDS));

        // The client sent a CONNECTION_CLOSE frame to the server.
        assertTrue(serverCloseLatch.await(5, SECONDS));
        await().atMost(5, SECONDS).until(serverSession::getStreams, hasSize(0));

        // Wait for the server-side session to be closed, typically 3 PTOs.
        ServerQuicConnection connection = (ServerQuicConnection)((QuicSession)serverSession).getQuicConnection();
        await().atMost(15, SECONDS).until(connection::getSessions, hasSize(0));
    }
}
