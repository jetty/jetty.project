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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SelectorManagerTest
{
    private QueuedThreadPool executor = new QueuedThreadPool();
    private TimerScheduler scheduler = new TimerScheduler();

    @BeforeEach
    public void prepare() throws Exception
    {
        executor.start();
        scheduler.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        scheduler.stop();
        executor.stop();
    }

    @Test
    public void testConnectTimeoutBeforeSuccessfulConnect() throws Exception
    {
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("localhost", 0));
        SocketAddress address = server.getLocalAddress();

        CountDownLatch connectionFinishedLatch = new CountDownLatch(1);
        CountDownLatch failedConnectionLatch = new CountDownLatch(1);
        long connectTimeout = 1000;
        SelectorManager selectorManager = new SelectorManager(executor, scheduler)
        {
            @Override
            protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key)
            {
                return new SocketChannelEndPoint((SocketChannel)channel, selector, key, getScheduler());
            }

            @Override
            protected boolean doFinishConnect(SelectableChannel channel) throws IOException
            {
                try
                {
                    assertTrue(failedConnectionLatch.await(connectTimeout * 2, TimeUnit.MILLISECONDS));
                    return super.doFinishConnect(channel);
                }
                catch (InterruptedException e)
                {
                    return false;
                }
                finally
                {
                    connectionFinishedLatch.countDown();
                }
            }

            @Override
            public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment)
            {
                ((Callback)attachment).succeeded();
                return new AbstractConnection(endpoint, executor)
                {
                    @Override
                    public void onFillable()
                    {
                    }
                };
            }

            @Override
            protected void connectionFailed(SelectableChannel channel, Throwable ex, Object attachment)
            {
                ((Callback)attachment).failed(ex);
            }
        };
        selectorManager.setConnectTimeout(connectTimeout);
        selectorManager.start();

        try
        {
            SocketChannel client1 = SocketChannel.open();
            client1.configureBlocking(false);
            assertFalse(client1.connect(address));
            selectorManager.connect(client1, new Callback()
            {
                @Override
                public void failed(Throwable x)
                {
                    failedConnectionLatch.countDown();
                }
            });
            assertTrue(failedConnectionLatch.await(connectTimeout * 2, TimeUnit.MILLISECONDS));
            assertFalse(client1.isOpen());

            // Wait for the first connect to finish, as the selector thread is waiting in doFinishConnect().
            assertTrue(connectionFinishedLatch.await(5, TimeUnit.SECONDS));

            // Verify that after the failure we can connect successfully.
            try (SocketChannel client2 = SocketChannel.open())
            {
                client2.configureBlocking(false);
                assertFalse(client2.connect(address));
                CountDownLatch successfulConnectionLatch = new CountDownLatch(1);
                selectorManager.connect(client2, new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        successfulConnectionLatch.countDown();
                    }
                });
                assertTrue(successfulConnectionLatch.await(connectTimeout * 2, TimeUnit.MILLISECONDS));
                assertTrue(client2.isOpen());
            }
        }
        finally
        {
            selectorManager.stop();
        }
    }
}
