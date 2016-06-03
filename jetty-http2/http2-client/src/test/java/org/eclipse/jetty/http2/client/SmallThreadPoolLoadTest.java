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

package org.eclipse.jetty.http2.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.ByteArrayOutputStream2;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class SmallThreadPoolLoadTest extends AbstractTest
{
    private final Logger logger = Log.getLogger(SmallThreadPoolLoadTest.class);
    private final AtomicLong requestIds = new AtomicLong();

    @Override
    protected void customizeContext(ServletContextHandler context)
    {
        QueuedThreadPool serverThreads = (QueuedThreadPool)context.getServer().getThreadPool();
        serverThreads.setDetailedDump(true);
        serverThreads.setMaxThreads(5);
        serverThreads.setLowThreadsThreshold(1);

        AbstractHTTP2ServerConnectionFactory h2 = connector.getBean(AbstractHTTP2ServerConnectionFactory.class);
        h2.setInitialSessionRecvWindow(Integer.MAX_VALUE);
    }

    @Test
    public void testConcurrentWithSmallServerThreadPool() throws Exception
    {
        start(new LoadServlet());

        // Only one connection to the server.
        Session session = newClient(new Session.Listener.Adapter());

        int runs = 10;
        int iterations = 512;
        boolean result = IntStream.range(0, 16).parallel()
                .mapToObj(i -> IntStream.range(0, runs)
                        .mapToObj(j -> run(session, iterations))
                        .reduce(true, (acc, res) -> acc && res))
                .reduce(true, (acc, res) -> acc && res);

        Assert.assertTrue(result);
    }

    private boolean run(Session session, int iterations)
    {
        try
        {
            CountDownLatch latch = new CountDownLatch(iterations);
            int factor = (logger.isDebugEnabled() ? 25 : 1) * 100;

            // Dumps the state of the client if the test takes too long.
            final Thread testThread = Thread.currentThread();
            Scheduler.Task task = client.getScheduler().schedule(() ->
            {
                logger.warn("Interrupting test, it is taking too long{}Server:{}{}{}Client:{}{}",
                        System.lineSeparator(), System.lineSeparator(), server.dump(),
                        System.lineSeparator(), System.lineSeparator(), client.dump());
                testThread.interrupt();
            }, iterations * factor, TimeUnit.MILLISECONDS);

            long successes = 0;
            long begin = System.nanoTime();
            for (int i = 0; i < iterations; ++i)
            {
                boolean success = test(session, latch);
                if (success)
                    ++successes;
            }

            Assert.assertTrue(latch.await(iterations, TimeUnit.SECONDS));
            long end = System.nanoTime();
            Assert.assertThat(successes, Matchers.greaterThan(0L));
            task.cancel();
            long elapsed = TimeUnit.NANOSECONDS.toMillis(end - begin);
            logger.info("{} requests in {} ms, {}/{} success/failure, {} req/s",
                    iterations, elapsed,
                    successes, iterations - successes,
                    elapsed > 0 ? iterations * 1000 / elapsed : -1);
            return true;
        }
        catch (Exception x)
        {
            x.printStackTrace();
            return false;
        }
    }

    private boolean test(Session session, CountDownLatch latch) throws Exception
    {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Choose a random method
        boolean download = random.nextBoolean();
        HttpMethod method = download ? HttpMethod.GET : HttpMethod.POST;

        int maxContentLength = 128 * 1024;
        int contentLength = random.nextInt(maxContentLength) + 1;

        long requestId = requestIds.incrementAndGet();
        MetaData.Request request = newRequest(method.asString(), "/" + requestId, new HttpFields());
        if (download)
            request.getFields().put("X-Download", String.valueOf(contentLength));
        HeadersFrame requestFrame = new HeadersFrame(request, null, download);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch requestLatch = new CountDownLatch(1);
        AtomicBoolean reset = new AtomicBoolean();
        session.newStream(requestFrame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    requestLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    requestLatch.countDown();
            }

            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                reset.set(true);
                requestLatch.countDown();
            }
        });
        if (!download)
        {
            Stream stream = promise.get(5, TimeUnit.SECONDS);
            stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(contentLength), true), Callback.NOOP);
        }

        boolean success = requestLatch.await(5, TimeUnit.SECONDS);
        if (success)
            latch.countDown();
        else
            logger.warn("Request {} took too long{}Server:{}{}{}Client:{}{}", requestId,
                    System.lineSeparator(), System.lineSeparator(), server.dump(),
                    System.lineSeparator(), System.lineSeparator(), client.dump());
        return !reset.get();
    }

    private static class LoadServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String method = request.getMethod().toUpperCase(Locale.ENGLISH);
            switch (method)
            {
                case "GET":
                {
                    int contentLength = request.getIntHeader("X-Download");
                    if (contentLength > 0)
                        response.getOutputStream().write(new byte[contentLength]);
                    break;
                }
                case "POST":
                {
                    int content_length=request.getContentLength();
                    ByteArrayOutputStream2 bout = new ByteArrayOutputStream2(content_length>0?content_length:16*1024);
                    IO.copy(request.getInputStream(), bout);
                    response.getOutputStream().write(bout.getBuf(),0,bout.getCount());
                    break;
                }
            }
        }
    }
}
