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

package org.eclipse.jetty.spdy;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.SPDYException;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AdvancedRunner.class)
//TODO: Uncomment comment lines and reimplement tests to fit new design
@Ignore("Doesn't work with new Flusher class, needs to be rewritten")
public class AsyncTimeoutTest
{
    EndPoint endPoint = new ByteArrayEndPoint();

    @Slow
    @Test
    public void testAsyncTimeoutInControlFrames() throws Exception
    {
        final long timeout = 1000;
        final TimeUnit unit = TimeUnit.MILLISECONDS;

        ByteBufferPool bufferPool = new MappedByteBufferPool();
        Executor threadPool = Executors.newCachedThreadPool();
        Scheduler scheduler = new TimerScheduler();
        scheduler.start(); // TODO need to use jetty lifecycles better here
        Generator generator = new Generator(bufferPool, new StandardCompressionFactory.StandardCompressor());
        Session session = new StandardSession(SPDY.V2, bufferPool, scheduler, new TestController(),
                endPoint, null, 1, null, generator, new FlowControlStrategy.None())
        {
//            @Override
            public void flush()
            {
                try
                {
                    unit.sleep(2 * timeout);
//                    super.flush();
                }
                catch (InterruptedException x)
                {
                    throw new SPDYException(x);
                }
            }
        };

        final CountDownLatch failedLatch = new CountDownLatch(1);
        session.syn(new SynInfo(timeout, unit, new Fields(), true, (byte)0), null, new Promise.Adapter<Stream>()
        {
            @Override
            public void failed(Throwable x)
            {
                failedLatch.countDown();
            }
        });

        Assert.assertTrue(failedLatch.await(2 * timeout, unit));
    }

    @Slow
    @Test
    public void testAsyncTimeoutInDataFrames() throws Exception
    {
        final long timeout = 1000;
        final TimeUnit unit = TimeUnit.MILLISECONDS;

        ByteBufferPool bufferPool = new MappedByteBufferPool();
        Executor threadPool = Executors.newCachedThreadPool();
        Scheduler scheduler = new TimerScheduler();
        scheduler.start();
        Generator generator = new Generator(bufferPool, new StandardCompressionFactory.StandardCompressor());
        Session session = new StandardSession(SPDY.V2, bufferPool, scheduler, new TestController(),
                endPoint, null, 1, null, generator, new FlowControlStrategy.None())
        {
//            @Override
            protected void write(ByteBuffer buffer, Callback callback)
            {
                try
                {
                    // Wait if we're writing the data frame (control frame's first byte is 0x80)
                    if (buffer.get(0) == 0)
                        unit.sleep(2 * timeout);
//                    super.write(buffer, callback);
                }
                catch (InterruptedException x)
                {
                    throw new SPDYException(x);
                }
            }
        };

        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), false, (byte)0), null);
        final CountDownLatch failedLatch = new CountDownLatch(1);
        stream.data(new StringDataInfo(timeout, unit, "data", true), new Callback.Adapter()
        {
            @Override
            public void failed(Throwable x)
            {
                failedLatch.countDown();
            }
        });

        Assert.assertTrue(failedLatch.await(2 * timeout, unit));
    }

    private static class TestController implements Controller
    {
        @Override
        public void write(Callback callback, ByteBuffer... buffers)
        {
            callback.succeeded();
        }

        @Override
        public void close(boolean onlyOutput)
        {
        }
    }
}
