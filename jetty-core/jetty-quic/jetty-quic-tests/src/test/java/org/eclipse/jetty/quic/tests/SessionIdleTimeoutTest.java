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

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.server.internal.ServerQuicConnection;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
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
        start(() -> new Session.Listener()
        {
            @Override
            public void onOpen(Session session)
            {
                serverSessionRef.set(session);
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
        client.getClientConnector().setIdleTimeout(Duration.ofMillis(idleTimeout));

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
            public void onDisconnect(Session session)
            {
                clientDisconnectLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);

        // RFC-9000[10.1] says the connection should be silently closed,
        // but we actually send a CLOSE_CONNECTION to the remote peer,
        // so that it can detect earlier that the connection is broken.
        assertTrue(serverCloseLatch.await(2 * idleTimeout, MILLISECONDS));
        assertTrue(serverDisconnectLatch.await(2 * idleTimeout, MILLISECONDS));
        assertTrue(clientDisconnectLatch.await(2 * idleTimeout, MILLISECONDS));

        // After a while, the client DatagramChannel should be closed.
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
        start(() -> new Session.Listener()
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
            }

            @Override
            public void onDisconnect(Session session)
            {
                serverDisconnectLatch.countDown();
            }
        });
        connector.setIdleTimeout(idleTimeout);

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

        // RFC-9000[10.1] says the connection should be silently closed,
        // but we actually send a CLOSE_CONNECTION to the remote peer,
        // so that it can detect earlier that the connection is broken.
        assertTrue(clientCloseLatch.await(2 * idleTimeout, MILLISECONDS));
        assertTrue(clientDisconnectLatch.await(2 * idleTimeout, MILLISECONDS));
        assertTrue(serverDisconnectLatch.await(2 * idleTimeout, MILLISECONDS));

        // After a while, the client DatagramChannel should be closed.
        await().atMost(5, SECONDS).until(((QuicSession)clientSession).getEndPoint()::isOpen, is(false));

        // On the server, the session should be gone.
        QuicSession serverSession = (QuicSession)serverSessionRef.get();
        await().atMost(5, SECONDS).until(((ServerQuicConnection)serverSession.getQuicConnection())::getSessions, empty());
    }

    @Test
    public void testClientAndServerMinimumIdleTimeout()
    {

    }

    @Test
    public void testIdleTimeoutWithKeepAlive()
    {

    }

    @Test
    public void testIdleTimeoutWithKeepAliveRemotePeerDoesNotRespond()
    {

    }
}
