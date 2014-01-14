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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.LeakDetector;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class SynDataReplyDataLoadTest extends AbstractTest
{
    private static final int TIMEOUT = 60 * 1000;
    private static final Logger logger = Log.getLogger(SynDataReplyDataLoadTest.class);

    @Test(timeout = TIMEOUT)
    @Ignore("Test needs to be rewritten")
    public void testSynDataReplyDataLoad() throws Exception
    {
        final AtomicLong leaks = new AtomicLong();
        LeakTrackingByteBufferPool serverBufferPool = new LeakTrackingByteBufferPool(new ArrayByteBufferPool())
        {
            @Override
            protected void leaked(LeakDetector.LeakInfo leakInfo)
            {
                leaks.incrementAndGet();
            }
        };
        LeakTrackingByteBufferPool clientBufferPool = new LeakTrackingByteBufferPool(new MappedByteBufferPool())
        {
            @Override
            protected void leaked(LeakDetector.LeakInfo leakInfo)
            {
                leaks.incrementAndGet();
            }
        };

        ServerSessionFrameListener listener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(synInfo.getHeaders(), false), new Callback.Adapter());
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        ByteBuffer buffer = dataInfo.asByteBuffer(true);
                        stream.data(new ByteBufferDataInfo(buffer, dataInfo.isClose()), new Callback.Adapter());
                    }
                };
            }
        };

        short spdyVersion = SPDY.V2;
        long idleTimeout = 2 * TIMEOUT;

        server = newServer();
        connector = new ServerConnector(server, null, null, serverBufferPool, 1,
                Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
                new SPDYServerConnectionFactory(spdyVersion, listener));
        connector.setIdleTimeout(idleTimeout);

        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName(clientExecutor.getName() + "-client");
        clientFactory = new SPDYClient.Factory(clientExecutor, null, clientBufferPool, null, idleTimeout);
        final Session session = startClient(spdyVersion, startServer(spdyVersion, listener), null);

        final Thread testThread = Thread.currentThread();
        Runnable timeout = new Runnable()
        {
            @Override
            public void run()
            {
                logger.warn("Interrupting test, it is taking too long");
                logger.warn("SERVER: {}", server.dump());
                logger.warn("CLIENT: {}", clientFactory.dump());
                testThread.interrupt();
            }
        };

        final int iterations = 500;
        final int count = 50;

        final Fields headers = new Fields();
        headers.put("method", "get");
        headers.put("url", "/");
        headers.put("version", "http/1.1");
        headers.put("host", "localhost:8080");
        headers.put("content-type", "application/octet-stream");

        final CountDownLatch latch = new CountDownLatch(count * iterations);
        session.addListener(new Session.StreamListener.Adapter()
        {
            @Override
            public void onStreamClosed(Stream stream)
            {
                latch.countDown();
            }
        });

        ExecutorService threadPool = Executors.newFixedThreadPool(count);
        List<Callable<Object>> tasks = new ArrayList<>();

        tasks.clear();
        for (int i = 0; i < count; ++i)
        {
            tasks.add(new Callable<Object>()
            {
                @Override
                public Object call() throws Exception
                {
                    synGetDataGet(session, headers, iterations);
                    return null;
                }
            });
        }
        Scheduler.Task syncTimeoutTask = clientFactory.getScheduler().schedule(timeout, TIMEOUT / 2, TimeUnit.MILLISECONDS);
        {
            long begin = System.nanoTime();
            List<Future<Object>> futures = threadPool.invokeAll(tasks);
            for (Future<Object> future : futures)
                future.get(iterations, TimeUnit.SECONDS);
            Assert.assertTrue(latch.await(count * iterations, TimeUnit.SECONDS));
            long end = System.nanoTime();
            System.err.printf("SYN+GET+DATA+GET completed in %d ms%n", TimeUnit.NANOSECONDS.toMillis(end - begin));
        }
        syncTimeoutTask.cancel();

        tasks.clear();
        for (int i = 0; i < count; ++i)
        {
            tasks.add(new Callable<Object>()
            {
                @Override
                public Object call() throws Exception
                {
                    synCompletedData(session, headers, iterations);
                    return null;
                }
            });
        }
        Scheduler.Task asyncTimeoutTask = clientFactory.getScheduler().schedule(timeout, TIMEOUT / 2, TimeUnit.MILLISECONDS);
        {
            long begin = System.nanoTime();
            List<Future<Object>> futures = threadPool.invokeAll(tasks);
            for (Future<Object> future : futures)
                future.get(iterations, TimeUnit.SECONDS);
            Assert.assertTrue(latch.await(count * iterations, TimeUnit.SECONDS));
            long end = System.nanoTime();
            System.err.printf("SYN+COMPLETED+DATA completed in %d ms%n", TimeUnit.NANOSECONDS.toMillis(end - begin));
        }
        asyncTimeoutTask.cancel();

        threadPool.shutdown();

        Assert.assertEquals(0, leaks.get());
    }

    private void synCompletedData(Session session, Fields headers, int iterations) throws Exception
    {
        final Map<Integer, Integer> counter = new ConcurrentHashMap<>(iterations);
        final CountDownLatch requestsLatch = new CountDownLatch(2 * iterations);
        for (int i = 0; i < iterations; ++i)
        {
            final AtomicInteger count = new AtomicInteger(2);
            final int index = i;
            counter.put(index, index);
            session.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter()
                    {
                        @Override
                        public void onReply(Stream stream, ReplyInfo replyInfo)
                        {
                            Assert.assertEquals(2, count.getAndDecrement());
                            requestsLatch.countDown();
                        }

                        @Override
                        public void onData(Stream stream, DataInfo dataInfo)
                        {
                            // TCP can split the data frames, so I may be receiving more than 1 data frame
                            dataInfo.asBytes(true);
                            if (dataInfo.isClose())
                            {
                                Assert.assertEquals(1, count.getAndDecrement());
                                counter.remove(index);
                                requestsLatch.countDown();
                            }
                        }
                    }, new Promise.Adapter<Stream>()
                    {
                        @Override
                        public void succeeded(Stream stream)
                        {
                            stream.data(new StringDataInfo("data_" + stream.getId(), true),
                                    new Callback.Adapter());
                        }
                    }
            );
        }
        Assert.assertTrue(requestsLatch.await(iterations, TimeUnit.SECONDS));
        Assert.assertTrue(counter.toString(), counter.isEmpty());
    }

    private void synGetDataGet(Session session, Fields headers, int iterations) throws Exception
    {
        final Map<Integer, Integer> counter = new ConcurrentHashMap<>(iterations);
        final CountDownLatch latch = new CountDownLatch(2 * iterations);
        for (int i = 0; i < iterations; ++i)
        {
            final AtomicInteger count = new AtomicInteger(2);
            final int index = i;
            counter.put(index, index);
            Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, false, (byte)0),
                    new StreamFrameListener.Adapter()
                    {
                        @Override
                        public void onReply(Stream stream, ReplyInfo replyInfo)
                        {
                            Assert.assertEquals(2, count.getAndDecrement());
                            latch.countDown();
                        }

                        @Override
                        public void onData(Stream stream, DataInfo dataInfo)
                        {
                            // TCP can split the data frames, so I may be receiving more than 1 data frame
                            dataInfo.asBytes(true);
                            if (dataInfo.isClose())
                            {
                                Assert.assertEquals(1, count.getAndDecrement());
                                counter.remove(index);
                                latch.countDown();
                            }
                        }
                    });
            stream.data(new StringDataInfo(5, TimeUnit.SECONDS, "data_" + stream.getId(), true));
        }
        Assert.assertTrue(latch.await(iterations, TimeUnit.SECONDS));
        Assert.assertTrue(counter.toString(), counter.isEmpty());
    }
}
