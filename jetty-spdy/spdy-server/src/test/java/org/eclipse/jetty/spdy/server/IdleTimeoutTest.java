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


package org.eclipse.jetty.spdy.server;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.GoAwayResultInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.Assert;
import org.junit.Test;

public class IdleTimeoutTest extends AbstractTest
{
    private final int idleTimeout = 1000;

    @Test
    public void testServerEnforcingIdleTimeout() throws Exception
    {
        server = newServer();
        connector = newSPDYServerConnector(server, new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true), new Callback.Adapter());
                return null;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        final CountDownLatch latch = new CountDownLatch(1);
        Session session = startClient(startServer(null), new SessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayInfo)
            {
                latch.countDown();
            }
        });

        session.syn(new SynInfo(new Fields(), true), null);

        Assert.assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testServerEnforcingIdleTimeoutWithUnrespondedStream() throws Exception
    {
        server = newServer();
        connector = newSPDYServerConnector(server, null);
        connector.setIdleTimeout(idleTimeout);

        final CountDownLatch latch = new CountDownLatch(1);
        Session session = startClient(startServer(null), new SessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayInfo)
            {
                latch.countDown();
            }
        });

        // The SYN is not replied, and the server should idle timeout
        session.syn(new SynInfo(new Fields(), true), null);

        Assert.assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testServerNotEnforcingIdleTimeoutWithPendingStream() throws Exception
    {
        server = newServer();
        connector = newSPDYServerConnector(server, new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                try
                {
                    Thread.sleep(2 * idleTimeout);
                    stream.reply(new ReplyInfo(true), new Callback.Adapter());
                    return null;
                }
                catch (InterruptedException x)
                {
                    Assert.fail();
                    return null;
                }
            }
        });
        connector.setIdleTimeout(idleTimeout);

        final CountDownLatch goAwayLatch = new CountDownLatch(1);
        Session session = startClient(startServer(null), new SessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayInfo)
            {
                goAwayLatch.countDown();
            }
        });

        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(new Fields(), true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                replyLatch.countDown();
            }
        });

        Assert.assertTrue(replyLatch.await(3 * idleTimeout, TimeUnit.MILLISECONDS));

        // Just make sure onGoAway has never been called, but don't wait too much
        Assert.assertFalse(goAwayLatch.await(idleTimeout / 2, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientEnforcingIdleTimeout() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        InetSocketAddress address = startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true), new Callback.Adapter());
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayInfo)
            {
                latch.countDown();
            }
        });

        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName(threadPool.getName() + "-client");
        clientFactory = newSPDYClientFactory(threadPool);
        clientFactory.start();
        SPDYClient client = clientFactory.newSPDYClient(SPDY.V2);
        client.setIdleTimeout(idleTimeout);
        Session session = client.connect(address, null);

        session.syn(new SynInfo(new Fields(), true), null);

        Assert.assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientEnforcingIdleTimeoutWithUnrespondedStream() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        InetSocketAddress address = startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayInfo)
            {
                latch.countDown();
            }
        });

        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName(threadPool.getName() + "-client");
        clientFactory = newSPDYClientFactory(threadPool);
        clientFactory.start();
        SPDYClient client = clientFactory.newSPDYClient(SPDY.V2);
        client.setIdleTimeout(idleTimeout);
        Session session = client.connect(address, null);

        session.syn(new SynInfo(new Fields(), true), null);

        Assert.assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientNotEnforcingIdleTimeoutWithPendingStream() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        InetSocketAddress address = startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true), new Callback.Adapter());
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayInfo)
            {
                latch.countDown();
            }
        });

        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName(threadPool.getName() + "-client");
        clientFactory = newSPDYClientFactory(threadPool);
        clientFactory.start();
        SPDYClient client = clientFactory.newSPDYClient(SPDY.V2);
        client.setIdleTimeout(idleTimeout);
        Session session = client.connect(address, null);

        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(new Fields(), true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                try
                {
                    Thread.sleep(2 * idleTimeout);
                    replyLatch.countDown();
                }
                catch (InterruptedException e)
                {
                    Assert.fail();
                }
            }
        });

        Assert.assertFalse(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        Assert.assertTrue(replyLatch.await(3 * idleTimeout, TimeUnit.MILLISECONDS));
    }
}
