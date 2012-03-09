/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.Assert;
import org.junit.Test;

public class IdleTimeoutTest extends AbstractTest
{
    @Test
    public void testServerEnforcingIdleTimeout() throws Exception
    {
        server = new Server();
        connector = newSPDYServerConnector(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true));
                return null;
            }
        });
        server.addConnector(connector);
        int maxIdleTime = 1000;
        connector.setMaxIdleTime(maxIdleTime);
        server.start();

        final CountDownLatch latch = new CountDownLatch(1);
        Session session = startClient(new InetSocketAddress("localhost", connector.getLocalPort()), new SessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayInfo goAwayInfo)
            {
                latch.countDown();
            }
        });

        session.syn(new SynInfo(true), null);

        Assert.assertTrue(latch.await(2 * maxIdleTime, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testServerEnforcingIdleTimeoutWithUnrespondedStream() throws Exception
    {
        server = new Server();
        connector = newSPDYServerConnector(null);
        server.addConnector(connector);
        int maxIdleTime = 1000;
        connector.setMaxIdleTime(maxIdleTime);
        server.start();

        final CountDownLatch latch = new CountDownLatch(1);
        Session session = startClient(new InetSocketAddress("localhost", connector.getLocalPort()), new SessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayInfo goAwayInfo)
            {
                latch.countDown();
            }
        });

        // The SYN is not replied, and the server should idle timeout
        session.syn(new SynInfo(true), null);

        Assert.assertTrue(latch.await(2 * maxIdleTime, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testServerNotEnforcingIdleTimeoutWithPendingStream() throws Exception
    {
        final int maxIdleTime = 1000;
        server = new Server();
        connector = newSPDYServerConnector(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                try
                {
                    Thread.sleep(2 * maxIdleTime);
                    stream.reply(new ReplyInfo(true));
                    return null;
                }
                catch (InterruptedException x)
                {
                    Assert.fail();
                    return null;
                }
            }
        });
        server.addConnector(connector);
        connector.setMaxIdleTime(maxIdleTime);
        server.start();

        final CountDownLatch latch = new CountDownLatch(1);
        Session session = startClient(new InetSocketAddress("localhost", connector.getLocalPort()), new SessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayInfo goAwayInfo)
            {
                latch.countDown();
            }
        });

        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                replyLatch.countDown();
            }
        });

        Assert.assertTrue(replyLatch.await(3 * maxIdleTime, TimeUnit.MILLISECONDS));
        Assert.assertFalse(latch.await(1000, TimeUnit.MILLISECONDS));
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
                stream.reply(new ReplyInfo(true));
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayInfo goAwayInfo)
            {
                latch.countDown();
            }
        });

        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName(threadPool.getName() + "-client");
        clientFactory = newSPDYClientFactory(threadPool);
        clientFactory.start();
        SPDYClient client = clientFactory.newSPDYClient(SPDY.V2);
        long maxIdleTime = 1000;
        client.setMaxIdleTime(maxIdleTime);
        Session session = client.connect(address, null).get(5, TimeUnit.SECONDS);

        session.syn(new SynInfo(true), null);

        Assert.assertTrue(latch.await(2 * maxIdleTime, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientEnforcingIdleTimeoutWithUnrespondedStream() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        InetSocketAddress address = startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayInfo goAwayInfo)
            {
                latch.countDown();
            }
        });

        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName(threadPool.getName() + "-client");
        clientFactory = newSPDYClientFactory(threadPool);
        clientFactory.start();
        SPDYClient client = clientFactory.newSPDYClient(SPDY.V2);
        long maxIdleTime = 1000;
        client.setMaxIdleTime(maxIdleTime);
        Session session = client.connect(address, null).get(5, TimeUnit.SECONDS);

        session.syn(new SynInfo(true), null);

        Assert.assertTrue(latch.await(2 * maxIdleTime, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientNotEnforcingIdleTimeoutWithPendingStream() throws Exception
    {
        final long maxIdleTime = 1000;
        final CountDownLatch latch = new CountDownLatch(1);
        InetSocketAddress address = startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true));
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayInfo goAwayInfo)
            {
                latch.countDown();
            }
        });

        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName(threadPool.getName() + "-client");
        clientFactory = newSPDYClientFactory(threadPool);
        clientFactory.start();
        SPDYClient client = clientFactory.newSPDYClient(SPDY.V2);
        client.setMaxIdleTime(maxIdleTime);
        Session session = client.connect(address, null).get(5, TimeUnit.SECONDS);

        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                try
                {
                    Thread.sleep(2 * maxIdleTime);
                    replyLatch.countDown();
                }
                catch (InterruptedException e)
                {
                    Assert.fail();
                }
            }
        });

        Assert.assertFalse(latch.await(2 * maxIdleTime, TimeUnit.MILLISECONDS));
        Assert.assertTrue(replyLatch.await(3 * maxIdleTime, TimeUnit.MILLISECONDS));
    }
}
