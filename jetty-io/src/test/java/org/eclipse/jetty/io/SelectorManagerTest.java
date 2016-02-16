//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SelectorManagerTest
{
    private QueuedThreadPool executor = new QueuedThreadPool();
    private TimerScheduler scheduler = new TimerScheduler();

    @Before
    public void prepare() throws Exception
    {
        executor.start();
        scheduler.start();
    }

    @After
    public void dispose() throws Exception
    {
        scheduler.stop();
        executor.stop();
    }

    @Slow
    @Test
    public void testConnectTimeoutBeforeSuccessfulConnect() throws Exception
    {
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("localhost", 0));
        SocketAddress address = server.getLocalAddress();

        final AtomicLong timeoutConnection = new AtomicLong();
        final long connectTimeout = 1000;
        SelectorManager selectorManager = new SelectorManager(executor, scheduler)
        {
            @Override
            protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key) throws IOException
            {
                SocketChannelEndPoint endp = new SocketChannelEndPoint(channel, selector, key, getScheduler());
                endp.setIdleTimeout(connectTimeout/2);
                return endp;
            }
            
            @Override
            protected boolean doFinishConnect(SelectableChannel channel) throws IOException
            {
                try
                {
                    long timeout = timeoutConnection.get();
                    if (timeout > 0)
                        TimeUnit.MILLISECONDS.sleep(timeout);
                    return super.doFinishConnect(channel);
                }
                catch (InterruptedException e)
                {
                    return false;
                }
            }

            @Override
            public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment) throws IOException
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
            client1.connect(address);
            long timeout = connectTimeout * 2;
            timeoutConnection.set(timeout);
            final CountDownLatch latch1 = new CountDownLatch(1);
            selectorManager.connect(client1, new Callback()
            {
                @Override
                public void failed(Throwable x)
                {
                    latch1.countDown();
                }
            });
            Assert.assertTrue(latch1.await(connectTimeout * 3, TimeUnit.MILLISECONDS));
            Assert.assertFalse(client1.isOpen());

            // Wait for the first connect to finish, as the selector thread is waiting in finishConnect().
            Thread.sleep(timeout);

            // Verify that after the failure we can connect successfully.
            try (SocketChannel client2 = SocketChannel.open())
            {
                client2.configureBlocking(false);
                client2.connect(address);
                timeoutConnection.set(0);
                final CountDownLatch latch2 = new CountDownLatch(1);
                selectorManager.connect(client2, new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        latch2.countDown();
                    }
                });
                Assert.assertTrue(latch2.await(connectTimeout * 5, TimeUnit.MILLISECONDS));
                Assert.assertTrue(client2.isOpen());
            }
        }
        finally
        {
            selectorManager.stop();
        }
    }
}
