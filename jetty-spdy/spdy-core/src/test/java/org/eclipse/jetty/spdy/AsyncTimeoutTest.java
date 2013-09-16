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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
        Session session = new StandardSession(SPDY.V2, bufferPool, threadPool, scheduler, new TestController(), null, 1, null, generator, new FlowControlStrategy.None())
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
            public void failed(Stream stream, Throwable x)
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
        Session session = new StandardSession(SPDY.V2, bufferPool, threadPool, scheduler, new TestController(), null, 1, null, generator, new FlowControlStrategy.None())
        {
            @Override
            protected void write(ByteBuffer buffer, Handler<FrameBytes> handler, FrameBytes frameBytes)
            {
                try
                {
                    // Wait if we're writing the data frame (control frame's first byte is 0x80)
                    if (buffer.get(0) == 0)
                        unit.sleep(2 * timeout);
                    super.write(buffer, handler, frameBytes);
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
            public void failed(Void context, Throwable x)
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
