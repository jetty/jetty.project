//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy;

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

import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Assert;
import org.junit.Test;

public class SynDataReplyDataLoadTest extends AbstractTest
{
    @Test
    public void testSynDataReplyDataLoad() throws Exception
    {
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(synInfo.getHeaders(), false));
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        ByteBuffer buffer = dataInfo.asByteBuffer(true);
                        stream.data(new ByteBufferDataInfo(buffer, dataInfo.isClose()));
                    }
                };
            }
        };
        final Session session = startClient(startServer(serverSessionFrameListener), null);

        final int iterations = 500;
        final int count = 50;

        final Headers headers = new Headers();
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
        {
            long begin = System.nanoTime();
            List<Future<Object>> futures = threadPool.invokeAll(tasks);
            for (Future<Object> future : futures)
                future.get(iterations, TimeUnit.SECONDS);
            Assert.assertTrue(latch.await(count * iterations, TimeUnit.SECONDS));
            long end = System.nanoTime();
            System.err.printf("SYN+GET+DATA+GET completed in %d ms%n", TimeUnit.NANOSECONDS.toMillis(end - begin));
        }

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
        {
            long begin = System.nanoTime();
            List<Future<Object>> futures = threadPool.invokeAll(tasks);
            for (Future<Object> future : futures)
                future.get(iterations, TimeUnit.SECONDS);
            Assert.assertTrue(latch.await(count * iterations, TimeUnit.SECONDS));
            long end = System.nanoTime();
            System.err.printf("SYN+COMPLETED+DATA completed in %d ms%n", TimeUnit.NANOSECONDS.toMillis(end - begin));
        }

        threadPool.shutdown();
    }

    private void synCompletedData(Session session, Headers headers, int iterations) throws Exception
    {
        final Map<Integer, Integer> counter = new ConcurrentHashMap<>(iterations);
        final CountDownLatch latch = new CountDownLatch(2 * iterations);
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
                    }, 0, TimeUnit.SECONDS, new Handler.Adapter<Stream>()
            {
                @Override
                public void completed(Stream stream)
                {
                    stream.data(new StringDataInfo("data_" + stream.getId(), true), 0, TimeUnit.SECONDS, null);
                }
            });
        }
        Assert.assertTrue(latch.await(iterations, TimeUnit.SECONDS));
        Assert.assertTrue(counter.toString(), counter.isEmpty());
    }

    private void synGetDataGet(Session session, Headers headers, int iterations) throws Exception
    {
        final Map<Integer, Integer> counter = new ConcurrentHashMap<>(iterations);
        final CountDownLatch latch = new CountDownLatch(2 * iterations);
        for (int i = 0; i < iterations; ++i)
        {
            final AtomicInteger count = new AtomicInteger(2);
            final int index = i;
            counter.put(index, index);
            Stream stream = session.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter()
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
            }).get(5, TimeUnit.SECONDS);
            stream.data(new StringDataInfo("data_" + stream.getId(), true)).get(5, TimeUnit.SECONDS);
        }
        Assert.assertTrue(latch.await(iterations, TimeUnit.SECONDS));
        Assert.assertTrue(counter.toString(), counter.isEmpty());
    }
}
