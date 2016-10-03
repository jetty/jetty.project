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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class SelectChannelEndPointInterestsTest
{
    private QueuedThreadPool threadPool;
    private Scheduler scheduler;
    private ServerSocketChannel connector;
    private SelectorManager selectorManager;

    public void init(final Interested interested) throws Exception
    {
        threadPool = new QueuedThreadPool();
        threadPool.start();

        scheduler = new TimerScheduler();
        scheduler.start();

        connector = ServerSocketChannel.open();
        connector.bind(new InetSocketAddress("localhost", 0));

        selectorManager = new SelectorManager(threadPool, scheduler)
        {

            @Override
            protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key) throws IOException
            {
                SocketChannelEndPoint endp = new SocketChannelEndPoint(channel, selector, key, getScheduler())
                {
                    @Override
                    protected void onIncompleteFlush()
                    {
                        super.onIncompleteFlush();
                        interested.onIncompleteFlush();
                    }
                };
                        
                endp.setIdleTimeout(60000);
                return endp;
            }

            @Override
            public Connection newConnection(SelectableChannel channel, final EndPoint endPoint, Object attachment)
            {
                return new AbstractConnection(endPoint, getExecutor())
                {
                    @Override
                    public void onOpen()
                    {
                        super.onOpen();
                        fillInterested();
                    }

                    @Override
                    public void onFillable()
                    {
                        interested.onFillable(endPoint, this);
                    }
                };
            }
        };
        selectorManager.start();
    }

    @After
    public void destroy() throws Exception
    {
        if (scheduler!=null)
            scheduler.stop();
        if (selectorManager != null)
            selectorManager.stop();
        if (connector != null)
            connector.close();
        if (threadPool != null)
            threadPool.stop();
    }

    @Test
    public void testReadBlockedThenWriteBlockedThenReadableThenWritable() throws Exception
    {
        final AtomicInteger size = new AtomicInteger(1024 * 1024);
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch latch1 = new CountDownLatch(1);
        final CountDownLatch latch2 = new CountDownLatch(1);
        final AtomicBoolean writeBlocked = new AtomicBoolean();
        init(new Interested()
        {
            @Override
            public void onFillable(EndPoint endPoint, AbstractConnection connection)
            {
                ByteBuffer input = BufferUtil.allocate(2);
                int read = fill(endPoint, input);

                if (read == 1)
                {
                    byte b = input.get();
                    if (b == 1)
                    {
                        connection.fillInterested();

                        ByteBuffer output = ByteBuffer.allocate(size.get());
                        endPoint.write(new Callback(){}, output);

                        latch1.countDown();
                    }
                    else
                    {
                        latch2.countDown();
                    }
                }
                else
                {
                    failure.set(new Exception("Unexpectedly read " + read + " bytes"));
                }
            }

            @Override
            public void onIncompleteFlush()
            {
                writeBlocked.set(true);
            }

            private int fill(EndPoint endPoint, ByteBuffer buffer)
            {
                try
                {
                    return endPoint.fill(buffer);
                }
                catch (IOException x)
                {
                    failure.set(x);
                    return 0;
                }
            }
        });

        Socket client = new Socket();
        client.connect(connector.getLocalAddress());
        client.setSoTimeout(5000);

        SocketChannel server = connector.accept();
        server.configureBlocking(false);
        selectorManager.accept(server);

        OutputStream clientOutput = client.getOutputStream();
        clientOutput.write(1);
        clientOutput.flush();
        Assert.assertTrue(latch1.await(5, TimeUnit.SECONDS));

        // We do not read to keep the socket write blocked

        clientOutput.write(2);
        clientOutput.flush();
        Assert.assertTrue(latch2.await(5, TimeUnit.SECONDS));

        // Sleep before reading to allow waking up the server only for read
        Thread.sleep(1000);

        // Now read what was written, waking up the server for write
        InputStream clientInput = client.getInputStream();
        while (size.getAndDecrement() > 0)
            clientInput.read();

        client.close();

        Assert.assertNull(failure.get());
    }

    private interface Interested
    {
        void onFillable(EndPoint endPoint, AbstractConnection connection);

        void onIncompleteFlush();
    }

}
