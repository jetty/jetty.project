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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.SPDYException;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.generator.Generator;
import org.junit.Assert;
import org.junit.Test;

public class AsyncTimeoutTest
{
    @Test
    public void testAsyncTimeoutInControlFrames() throws Exception
    {
        final long timeout = 1000;
        final TimeUnit unit = TimeUnit.MILLISECONDS;

        ByteBufferPool bufferPool = new StandardByteBufferPool();
        Executor threadPool = Executors.newCachedThreadPool();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Generator generator = new Generator(new StandardByteBufferPool(), new StandardCompressionFactory.StandardCompressor());
        Session session = new StandardSession(SPDY.V2, bufferPool, threadPool, scheduler, new TestController(), null, 1, null, generator)
        {
            @Override
            public void flush()
            {
                try
                {
                    unit.sleep(2 * timeout);
                    super.flush();
                }
                catch (InterruptedException x)
                {
                    throw new SPDYException(x);
                }
            }
        };

        final CountDownLatch failedLatch = new CountDownLatch(1);
        session.syn(new SynInfo(true), null, timeout, unit, new Handler<Stream>()
        {
            @Override
            public void completed(Stream stream)
            {
            }

            @Override
            public void failed(Throwable x)
            {
                failedLatch.countDown();
            }
        });

        Assert.assertTrue(failedLatch.await(2 * timeout, unit));
    }

    @Test
    public void testAsyncTimeoutInDataFrames() throws Exception
    {
        final long timeout = 1000;
        final TimeUnit unit = TimeUnit.MILLISECONDS;

        ByteBufferPool bufferPool = new StandardByteBufferPool();
        Executor threadPool = Executors.newCachedThreadPool();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Generator generator = new Generator(new StandardByteBufferPool(), new StandardCompressionFactory.StandardCompressor());
        Session session = new StandardSession(SPDY.V2, bufferPool, threadPool, scheduler, new TestController(), null, 1, null, generator)
        {
            private final AtomicInteger flushes = new AtomicInteger();

            @Override
            public void flush()
            {
                try
                {
                    int flushes = this.flushes.incrementAndGet();
                    if (flushes == 3)
                        unit.sleep(2 * timeout);
                    super.flush();
                }
                catch (InterruptedException x)
                {
                    throw new SPDYException(x);
                }
            }
        };

        Stream stream = session.syn(new SynInfo(false), null).get(5, TimeUnit.SECONDS);
        final CountDownLatch failedLatch = new CountDownLatch(1);
        stream.data(new StringDataInfo("data", true), timeout, unit, new Handler<Void>()
        {
            @Override
            public void completed(Void context)
            {
            }

            @Override
            public void failed(Throwable x)
            {
                failedLatch.countDown();
            }
        });

        Assert.assertTrue(failedLatch.await(2 * timeout, unit));
    }

    private static class TestController implements Controller<StandardSession.FrameBytes>
    {
        @Override
        public int write(ByteBuffer buffer, Handler<StandardSession.FrameBytes> handler, StandardSession.FrameBytes context)
        {
            handler.completed(context);
            return buffer.remaining();
        }

        @Override
        public void close(boolean onlyOutput)
        {
        }
    }
}
