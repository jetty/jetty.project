//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client;

import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class LivelockTest
{
    @Parameterized.Parameters(name = "server={0}, client={1}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();
        // Server-live-lock, Client-live-lock
        data.add(new Object[] { true, true });
        data.add(new Object[] { true, false });
        data.add(new Object[] { false, true });
        data.add(new Object[] { false, false });
        return data;
    }

    @Parameterized.Parameter(0)
    public boolean serverLiveLock;

    @Parameterized.Parameter(1)
    public boolean clientLiveLock;

    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    @Before
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
    
    @After
    public void after() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }

    @Test
    public void testLivelock() throws Exception
    {
        // This test applies a moderate connect/request load (5/s) over 5 seconds,
        // with a connect timeout of 1000, so any delayed connects will be detected.
        // NonBlocking actions are submitted to both the client and server
        // ManagedSelectors that submit themselves in an attempt to cause a live lock
        // as there will always be an action available to run.
        
        int count = 5;
        HttpClientTransport transport = new HttpClientTransportOverHTTP(1);
        client = new HttpClient(transport, null);
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
        Logger clientLog = Log.getLogger("TESTClient");
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
                            if(result.getRequestFailure() != null)
                                clientLog.warn(result.getRequestFailure());
                            if(result.getResponseFailure() != null)
                                clientLog.warn(result.getResponseFailure());
                        }
                    });
            sleep(pause);
        }
        Assert.assertTrue(latch.await(2 * pause * count, TimeUnit.MILLISECONDS));

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
