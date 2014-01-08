//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

        SocketChannel client = SocketChannel.open();
        client.configureBlocking(false);
        client.connect(address);

        final AtomicBoolean timeoutConnection = new AtomicBoolean();
        final long connectTimeout = 1000;
        SelectorManager selectorManager = new SelectorManager(executor, scheduler)
        {
            @Override
            protected EndPoint newEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey selectionKey) throws IOException
            {
                return new SelectChannelEndPoint(channel, selector, selectionKey, getScheduler(), connectTimeout / 2);
            }

            @Override
            protected boolean finishConnect(SocketChannel channel) throws IOException
            {
                try
                {
                    if (timeoutConnection.get())
                        TimeUnit.MILLISECONDS.sleep(connectTimeout * 2);
                    return super.finishConnect(channel);
                }
                catch (InterruptedException e)
                {
                    return false;
                }
            }

            @Override
            public Connection newConnection(SocketChannel channel, EndPoint endpoint, Object attachment) throws IOException
            {
                return new AbstractConnection(endpoint, executor)
                {
                    @Override
                    public void onFillable()
                    {
                    }
                };
            }

            @Override
            protected void connectionFailed(SocketChannel channel, Throwable ex, Object attachment)
            {
                ((Callback)attachment).failed(ex);
            }
        };
        selectorManager.setConnectTimeout(connectTimeout);
        selectorManager.start();

        try
        {
            timeoutConnection.set(true);
            final CountDownLatch latch1 = new CountDownLatch(1);
            selectorManager.connect(client, new Callback.Adapter()
            {
                @Override
                public void failed(Throwable x)
                {
                    latch1.countDown();
                }
            });
            Assert.assertTrue(latch1.await(connectTimeout * 3, TimeUnit.MILLISECONDS));

            // Verify that after the failure we can connect successfully
            timeoutConnection.set(false);
            final CountDownLatch latch2 = new CountDownLatch(1);
            selectorManager.connect(client, new Callback.Adapter()
            {
                @Override
                public void failed(Throwable x)
                {
                    latch2.countDown();
                }
            });
            Assert.assertTrue(latch2.await(connectTimeout, TimeUnit.MILLISECONDS));
        }
        finally
        {
            selectorManager.stop();
        }
    }
}
