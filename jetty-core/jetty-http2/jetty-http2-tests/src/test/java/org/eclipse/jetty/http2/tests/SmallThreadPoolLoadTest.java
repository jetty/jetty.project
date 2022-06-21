//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.tests;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SmallThreadPoolLoadTest extends AbstractTest
{
    private final Logger logger = LoggerFactory.getLogger(SmallThreadPoolLoadTest.class);
    private final AtomicLong requestIds = new AtomicLong();

    @Override
    protected void prepareServer(ConnectionFactory... connectionFactories)
    {
        super.prepareServer(connectionFactories);
        QueuedThreadPool serverThreads = (QueuedThreadPool)server.getThreadPool();
        serverThreads.setDetailedDump(true);
        serverThreads.setMaxThreads(5);
        serverThreads.setLowThreadsThreshold(1);
        AbstractHTTP2ServerConnectionFactory h2 = connector.getBean(AbstractHTTP2ServerConnectionFactory.class);
        h2.setInitialSessionRecvWindow(Integer.MAX_VALUE);
    }

    @Test
    public void testConcurrentWithSmallServerThreadPool() throws Exception
    {
        start(new LoadHandler());

        // Only one connection to the server.
        Session session = newClientSession(new Session.Listener() {});

        int runs = 10;
        int iterations = 512;
        boolean result = IntStream.range(0, 16).parallel()
            .mapToObj(i -> IntStream.range(0, runs)
                .mapToObj(j -> run(session, iterations))
                .reduce(true, Boolean::logicalAnd))
            .reduce(true, Boolean::logicalAnd);

        assertTrue(result);
    }

    private boolean run(Session session, int iterations)
    {
        try
        {
            CountDownLatch latch = new CountDownLatch(iterations);
            long factor = (logger.isDebugEnabled() ? 25 : 1) * 100;

            // Dumps the state of the client if the test takes too long.
            Thread testThread = Thread.currentThread();
            Scheduler.Task task = http2Client.getScheduler().schedule(() ->
            {
                logger.warn("Interrupting test, it is taking too long - \nServer: \n" +
                    server.dump() + "\nClient: \n" + http2Client.dump());
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

            assertTrue(latch.await(iterations, TimeUnit.SECONDS));
            long end = System.nanoTime();
            assertThat(successes, Matchers.greaterThan(0L));
            task.cancel();
            long elapsed = TimeUnit.NANOSECONDS.toMillis(end - begin);
            logger.info("{} requests in {} ms, {}/{} success/failure, {} req/s",
                iterations, elapsed,
                successes, iterations - successes,
                elapsed > 0 ? iterations * 1000L / elapsed : -1);
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

        MetaData.Request request = newRequest(method.asString(), "/" + requestId,
            download ? HttpFields.build().put("X-Download", String.valueOf(contentLength)) : HttpFields.EMPTY);

        HeadersFrame requestFrame = new HeadersFrame(request, null, download);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicBoolean reset = new AtomicBoolean();
        session.newStream(requestFrame, promise, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    responseLatch.countDown();
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                data.release();
                stream.demand();
                if (data.frame().isEndStream())
                    responseLatch.countDown();
            }

            @Override
            public void onReset(Stream stream, ResetFrame frame, Callback callback)
            {
                reset.set(true);
                responseLatch.countDown();
                callback.succeeded();
            }
        });
        if (!download)
        {
            Stream stream = promise.get(5, TimeUnit.SECONDS);
            stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(contentLength), true), Callback.NOOP);
        }

        boolean success = responseLatch.await(5, TimeUnit.SECONDS);
        if (success)
            latch.countDown();
        else
            logger.warn("Request {} took too long - \nServer: \n" +
                server.dump() + "\nClient: \n" + http2Client.dump(), requestId);
        return !reset.get();
    }

    private static class LoadHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            switch (HttpMethod.fromString(request.getMethod()))
            {
                case GET ->
                {
                    int contentLength = (int)request.getHeaders().getLongField("X-Download");
                    if (contentLength > 0)
                        response.write(true, ByteBuffer.wrap(new byte[contentLength]), callback);
                    else
                        callback.succeeded();
                }
                case POST ->
                {
                    Content.copy(request, response, callback);
                }
            }
        }
    }
}
