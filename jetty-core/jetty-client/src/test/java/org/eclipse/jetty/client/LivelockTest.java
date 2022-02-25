//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.channels.Selector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LivelockTest
{
    public static Stream<Arguments> modes()
    {
        return Stream.of(
            // Server-live-lock, Client-live-lock
            Arguments.of(true, true),
            Arguments.of(true, false),
            Arguments.of(false, true),
            Arguments.of(false, false)
        );
    }

    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    @BeforeEach
    public void before() throws Exception
    {
        Handler handler = new EmptyServerHandler();
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }

    @ParameterizedTest(name = "{index} ==> serverLiveLock={0}, clientLiveLock={1}")
    @MethodSource("modes")
    public void testLivelock(boolean serverLiveLock, boolean clientLiveLock) throws Exception
    {
        // This test applies a moderate connect/request load (5/s) over 5 seconds,
        // with a connect timeout of 1000, so any delayed connects will be detected.
        // NonBlocking actions are submitted to both the client and server
        // ManagedSelectors that submit themselves in an attempt to cause a live lock
        // as there will always be an action available to run.

        int count = 5;
        HttpClientTransport transport = new HttpClientTransportOverHTTP(1);
        client = new HttpClient(transport);
        client.setMaxConnectionsPerDestination(2 * count);
        client.setMaxRequestsQueuedPerDestination(2 * count);
        client.setSocketAddressResolver(new SocketAddressResolver.Sync());
        client.setConnectBlocking(false);
        client.setConnectTimeout(1000);
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client.setExecutor(clientThreads);
        client.start();

        AtomicBoolean busy = new AtomicBoolean(true);

        if (clientLiveLock)
        {
            ManagedSelector clientSelector = client.getContainedBeans(ManagedSelector.class).stream().findAny().get();
            busyLiveLock(busy, clientSelector);
        }

        if (serverLiveLock)
        {
            ManagedSelector serverSelector = connector.getContainedBeans(ManagedSelector.class).stream().findAny().get();
            busyLiveLock(busy, serverSelector);
        }

        int requestRate = 5;
        long pause = 1000 / requestRate;
        Logger clientLog = LoggerFactory.getLogger("TESTClient");
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            client.newRequest("localhost", connector.getLocalPort())
                .path("/" + i)
                .send(result ->
                {
                    if (result.isSucceeded() && result.getResponse().getStatus() == HttpStatus.OK_200)
                        latch.countDown();
                    else
                    {
                        if (result.getRequestFailure() != null)
                            clientLog.warn("Request Failure on {}", result, result.getRequestFailure());
                        if (result.getResponseFailure() != null)
                            clientLog.warn("Response Failure on {}", result, result.getResponseFailure());
                    }
                });
            sleep(pause);
        }
        assertTrue(latch.await(2 * pause * count, TimeUnit.MILLISECONDS));

        // Exit the livelocks.
        busy.set(false);
    }

    private void busyLiveLock(AtomicBoolean busy, ManagedSelector managedSelector)
    {
        ManagedSelector.SelectorUpdate liveLock = new ManagedSelector.SelectorUpdate()
        {
            @Override
            public void update(Selector selector)
            {
                sleep(10);
                if (busy.get())
                    managedSelector.submit(this);
            }
        };
        managedSelector.submit(liveLock);
    }

    private void sleep(long millis)
    {
        try
        {
            TimeUnit.MILLISECONDS.sleep(millis);
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException(x);
        }
    }
}
