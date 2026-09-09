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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ConnectionPoolReservationTest
{
    private Server server;
    private ServerConnector connector;
    private FailingClientConnector clientConnector;
    private HttpClient client;

    private void start(Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector = new FailingClientConnector();
        clientConnector.setSelectors(1);
        // Connect in blocking mode, so that ClientConnector always uses
        // SelectorManager.accept() rather than SelectorManager.connect().
        clientConnector.setConnectBlocking(true);
        clientConnector.setExecutor(clientThreads);
        client = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        // Only one connection, so that the test fails
        // fast if the reserved entry is not released.
        client.setMaxConnectionsPerDestination(1);
        client.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    public void testConnectionCreationFailureReleasesReservedEntry(Failure failure) throws Exception
    {
        start(new EmptyServerHandler());

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS);
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();

        clientConnector.failure.set(failure);

        ExecutionException x = assertThrows(ExecutionException.class, request::send);
        assertInstanceOf(failure.expected, x.getCause());

        // The reserved entry must have been removed, and the
        // pending count restored, otherwise the entry is leaked.
        assertEquals(0, connectionPool.getPendingConnectionCount());
        assertEquals(0, connectionPool.getConnectionCount());

        // Verify that the same destination can create a new connection.
        clientConnector.failure.set(null);

        request = client.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS);
        assertSame(destination, client.resolveDestination(request));
        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(0, connectionPool.getPendingConnectionCount());
        assertEquals(1, connectionPool.getConnectionCount());
        assertEquals(1, connectionPool.getIdleConnectionCount());
    }

    public enum Failure
    {
        NO_SELECTOR(ClosedChannelException.class),
        REGISTRATION(ClosedChannelException.class),
        NEW_ENDPOINT(IllegalStateException.class),
        NEW_CONNECTION(IOException.class);

        private final Class<? extends Throwable> expected;

        Failure(Class<? extends Throwable> expected)
        {
            this.expected = expected;
        }
    }

    private static class FailingClientConnector extends ClientConnector
    {
        private final AtomicReference<Failure> failure = new AtomicReference<>();

        @Override
        protected EndPoint newEndPoint(SelectableChannel selectable, ManagedSelector selector, SelectionKey selectionKey)
        {
            if (failure.get() == Failure.NEW_ENDPOINT)
                throw new IllegalStateException("newEndPoint failure");
            return super.newEndPoint(selectable, selector, selectionKey);
        }

        @Override
        protected org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
        {
            if (failure.get() == Failure.NEW_CONNECTION)
                throw new IOException("newConnection failure");
            return super.newConnection(endPoint, context);
        }

        @Override
        protected SelectorManager newSelectorManager()
        {
            return new ClientSelectorManager(getExecutor(), getScheduler(), getSelectors())
            {
                @Override
                protected ManagedSelector chooseSelector()
                {
                    if (failure.get() == Failure.NO_SELECTOR)
                        return null;
                    return super.chooseSelector();
                }

                @Override
                public void accept(SelectableChannel channel, Object attachment)
                {
                    if (failure.get() == Failure.REGISTRATION)
                        IO.close(channel);
                    super.accept(channel, attachment);
                }
            };
        }
    }
}
