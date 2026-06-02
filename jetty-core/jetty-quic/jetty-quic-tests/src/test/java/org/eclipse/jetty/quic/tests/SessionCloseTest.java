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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.common.SessionContainer;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class SessionCloseTest extends AbstractQuicTest
{
    @Test
    public void testClientConnectTimeout() throws Exception
    {
        start(() -> new Session.Listener() {});

        long connectTimeout = 1000;
        client.getClientConnector().setConnectTimeout(Duration.ofMillis(connectTimeout));

        SocketAddress remoteAddress = new InetSocketAddress("localhost", connector.getLocalPort());
        // Stop the server connector so that client packets won't reach the server.
        connector.stop();

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        CompletableFuture<Session> future = new CompletableFuture<>();
        client.connect(remoteAddress, new Session.Listener()
        {
            @Override
            public void onFailure(Session session, Throwable failure)
            {
                clientFailureLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session)
            {
                clientDisconnectLatch.countDown();
            }
        }, Promise.Invocable.toPromise(future));

        assertTrue(clientFailureLatch.await(2 * connectTimeout, MILLISECONDS));
        assertTrue(clientDisconnectLatch.await(5, SECONDS));

        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(5, SECONDS));
        assertInstanceOf(QuicException.class, failure.getCause());
    }

    @Test
    public void testClientFailsDuringTLSHandshakeEncryptionLevelInitial() throws Exception
    {
        // TODO
    }

    @Test
    public void testServerFailsDuringTLSHandshakeEncryptionLevelInitial() throws Exception
    {
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(() -> new Session.Listener()
        {
            @Override
            public void onFailure(Session session, Throwable failure)
            {
                serverFailureLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session)
            {
                serverDisconnectLatch.countDown();
            }
        });

        // Make the server fail with no cipher suites in common.
        connector.getServerQuicConfiguration().setCipherSuites(List.of());

        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        CompletableFuture<Session> future = new CompletableFuture<>();
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
        }, Promise.Invocable.toPromise(future));

        assertTrue(serverFailureLatch.await(5, SECONDS));
        assertTrue(serverDisconnectLatch.await(5, SECONDS));
        assertTrue(clientCloseLatch.await(5, SECONDS));
        assertTrue(clientDisconnectLatch.await(5, SECONDS));
        assertThrows(ExecutionException.class, () -> future.get(5, SECONDS));
    }

    @Test
    public void testClientFailsDuringTLSHandshakeEncryptionLevelHandshake() throws Exception
    {
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(() -> new Session.Listener()
        {
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

        // Make the client fail with no protocols in common.
        client.setApplicationProtocols(List.of("http/1.1"));

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        CompletableFuture<Session> future = new CompletableFuture<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onFailure(Session session, Throwable failure)
            {
                clientFailureLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session)
            {
                clientDisconnectLatch.countDown();
            }
        }, Promise.Invocable.toPromise(future));

        assertTrue(serverCloseLatch.await(5, SECONDS));
        assertTrue(serverDisconnectLatch.await(5, SECONDS));
        assertTrue(clientFailureLatch.await(5, SECONDS));
        assertTrue(clientDisconnectLatch.await(5, SECONDS));
        assertThrows(ExecutionException.class, () -> future.get(5, SECONDS));
    }

    @Test
    public void testServerFailsDuringTLSHandshakeEncryptionLevelHandshake() throws Exception
    {
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(() -> new Session.Listener()
        {
            @Override
            public void onFailure(Session session, Throwable failure)
            {
                serverFailureLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session)
            {
                serverDisconnectLatch.countDown();
            }
        });

        // The host name is different from "localhost", so
        // the server won't be able to match the certificate.
        InetAddress serverAddress = InetAddress.getLocalHost();
        assumeFalse("localhost".equalsIgnoreCase(serverAddress.getHostName()));

        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        CompletableFuture<Session> future = new CompletableFuture<>();
        client.connect(new InetSocketAddress(serverAddress, connector.getLocalPort()), new Session.Listener()
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
        }, Promise.Invocable.toPromise(future));

        assertTrue(serverFailureLatch.await(5, SECONDS));
        assertTrue(serverDisconnectLatch.await(5, SECONDS));
        assertTrue(clientCloseLatch.await(5, SECONDS));
        assertTrue(clientDisconnectLatch.await(5, SECONDS));
        assertThrows(ExecutionException.class, () -> future.get(5, SECONDS));
    }

    @Test
    public void testClientCloses() throws Exception
    {
        // TODO
    }

    @Test
    public void testServerCloses() throws Exception
    {
        // TODO
    }

    @Test
    public void testClientClosesThenReceivesMoreFramesThatWillBeDropped()
    {
        // TODO
    }

    @Test
    public void testServerClosesThenReceivesMoreFramesThatWillBeDropped()
    {
        // TODO
    }

    @Test
    public void testClientStopClosesAllConnections() throws Exception
    {
        CountDownLatch serverCloseLatch = new CountDownLatch(2);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(2);
        start(() -> new Session.Listener()
        {
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

        CountDownLatch clientDisconnectLatch = new CountDownLatch(2);
        CompletableFuture<Session> future1 = new CompletableFuture<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onDisconnect(Session session)
            {
                clientDisconnectLatch.countDown();
            }
        }, Promise.Invocable.toPromise(future1));
        assertNotNull(future1.get(5, SECONDS));

        CompletableFuture<Session> future2 = new CompletableFuture<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onDisconnect(Session session)
            {
                clientDisconnectLatch.countDown();
            }
        }, Promise.Invocable.toPromise(future2));
        assertNotNull(future2.get(5, SECONDS));

        client.stop();

        assertTrue(clientDisconnectLatch.await(5, SECONDS));
        assertTrue(serverCloseLatch.await(5, SECONDS));
        assertTrue(serverDisconnectLatch.await(5, SECONDS));

        await().atMost(5, SECONDS).until(() -> client.getBean(SessionContainer.class).isEmpty());
        await().atMost(5, SECONDS).until(() -> connector.getBean(SessionContainer.class).isEmpty());
    }

    @Test
    public void testServerStopClosesAllConnections() throws Exception
    {
        CountDownLatch serverDisconnectLatch = new CountDownLatch(2);
        start(() -> new Session.Listener()
        {
            @Override
            public void onDisconnect(Session session)
            {
                serverDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientCloseLatch = new CountDownLatch(2);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(2);
        CompletableFuture<Session> future1 = new CompletableFuture<>();
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
        }, Promise.Invocable.toPromise(future1));
        assertNotNull(future1.get(5, SECONDS));

        CompletableFuture<Session> future2 = new CompletableFuture<>();
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
        }, Promise.Invocable.toPromise(future2));
        assertNotNull(future2.get(5, SECONDS));

        server.stop();

        assertTrue(serverDisconnectLatch.await(5, SECONDS));
        assertTrue(clientCloseLatch.await(5, SECONDS));
        assertTrue(clientDisconnectLatch.await(5, SECONDS));

        await().atMost(5, SECONDS).until(() -> connector.getBean(SessionContainer.class).isEmpty());
        await().atMost(5, SECONDS).until(() -> client.getBean(SessionContainer.class).isEmpty());
    }
}
