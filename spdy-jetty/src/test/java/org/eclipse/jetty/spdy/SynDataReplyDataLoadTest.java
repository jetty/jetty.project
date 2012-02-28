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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
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
    public void testConcurrentSynDataReplyData() throws Exception
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
                        ByteBuffer buffer = dataInfo.asByteBuffer();
                        stream.data(new ByteBufferDataInfo(buffer, true));
                    }
                };
            }
        };
        final Session session = startClient(startServer(serverSessionFrameListener), null);

        final int iterations = 50;
        final int count = 50;

        final Headers headers = new Headers();
        headers.put("method", "get");
        headers.put("path", "/");
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

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < count; ++i)
        {
            tasks.add(new Callable<Object>()
            {
                @Override
                public Object call() throws Exception
                {
                    process(session, headers, iterations);
                    return null;
                }
            });
        }

        ExecutorService threadPool = Executors.newFixedThreadPool(count);
        List<Future<Object>> futures = threadPool.invokeAll(tasks);
        for (Future<Object> future : futures)
            future.get();
        Assert.assertTrue(latch.await(count * iterations * 100, TimeUnit.MILLISECONDS));
        threadPool.shutdown();
    }

    private void process(Session session, Headers headers, int iterations) throws Exception
    {
        for (int i = 0; i < iterations; ++i)
        {
            final CountDownLatch latch = new CountDownLatch(2);
            Stream stream = session.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter()
            {
                @Override
                public void onReply(Stream stream, ReplyInfo replyInfo)
                {
                    Assert.assertEquals(2, latch.getCount());
                    latch.countDown();
                }

                @Override
                public void onData(Stream stream, DataInfo dataInfo)
                {
                    Assert.assertEquals(1, latch.getCount());
                    ByteBuffer buffer = ByteBuffer.allocate(dataInfo.getContentLength());
                    dataInfo.getContent(buffer);
                    Assert.assertTrue(dataInfo.isConsumed());
                    latch.countDown();
                }
            }).get();
            stream.data(new StringDataInfo("data_" + stream.getId(), true));
            Assert.assertTrue("process() failed for stream=" + stream.getId(), latch.await(5, TimeUnit.SECONDS));
        }
    }
}
