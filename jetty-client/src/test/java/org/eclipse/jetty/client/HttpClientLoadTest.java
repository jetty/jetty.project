//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.toolchain.test.annotation.Stress;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientLoadTest extends AbstractHttpClientServerTest
{
    private final Logger logger = Log.getLogger(HttpClientLoadTest.class);

    public HttpClientLoadTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Stress("High I/O, High CPU")
    @Slow
    @Test
    public void testIterative() throws Exception
    {
        start(new LoadHandler());

        client.setMaxConnectionsPerAddress(32768);
        client.setMaxQueueSizePerAddress(1024 * 1024);

        Random random = new Random();
        int iterations = 200;
        CountDownLatch latch = new CountDownLatch(iterations);
        List<String> failures = new ArrayList<>();

        int factor = logger.isDebugEnabled() ? 25 : 1;
        factor *= "http".equalsIgnoreCase(scheme) ? 10 : 1000;

        // Dumps the state of the client if the test takes too long
        final Thread testThread = Thread.currentThread();
        client.getScheduler().schedule(new Runnable()
        {
            @Override
            public void run()
            {
                logger.warn("Interrupting test, it is taking too long");
                for (String host : Arrays.asList("localhost", "127.0.0.1"))
                {
                    HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, connector.getLocalPort());
                    for (Connection connection : new ArrayList<>(destination.getActiveConnections()))
                    {
                        HttpConnection active = (HttpConnection)connection;
                        logger.warn(active.getEndPoint() + " exchange " + active.getExchange());
                    }
                }
                testThread.interrupt();
            }
        }, iterations * factor, TimeUnit.MILLISECONDS);

        long begin = System.nanoTime();
        for (int i = 0; i < iterations; ++i)
        {
            test(random, latch, failures);
        }
        Assert.assertTrue(latch.await(iterations, TimeUnit.SECONDS));
        long end = System.nanoTime();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(end - begin);
        logger.info("{} requests in {} ms, {} req/s", iterations, elapsed, elapsed > 0 ? iterations * 1000 / elapsed : -1);

        Assert.assertTrue(failures.toString(), failures.isEmpty());
    }

    private void test(Random random, final CountDownLatch latch, final List<String> failures)
    {
        int maxContentLength = 64 * 1024;

        // Choose a random destination
        String host = random.nextBoolean() ? "localhost" : "127.0.0.1";
        Request request = client.newRequest(host, connector.getLocalPort()).scheme(scheme);

        // Choose a random method
        HttpMethod method = random.nextBoolean() ? HttpMethod.GET : HttpMethod.POST;
        request.method(method);

        boolean ssl = "https".equalsIgnoreCase(scheme);

        // Choose randomly whether to close the connection on the client or on the server
        if (!ssl && random.nextBoolean())
            request.header("Connection", "close");
        else if (!ssl && random.nextBoolean())
            request.header("X-Close", "true");

        switch (method)
        {
            case GET:
                // Randomly ask the server to download data upon this GET request
                if (random.nextBoolean())
                    request.header("X-Download", String.valueOf(random.nextInt(maxContentLength) + 1));
                break;
            case POST:
                int contentLength = random.nextInt(maxContentLength) + 1;
                request.header("X-Upload", String.valueOf(contentLength));
                request.content(new BytesContentProvider(new byte[contentLength]));
                break;
        }

        request.send(new Response.Listener.Empty()
        {
            private final AtomicInteger contentLength = new AtomicInteger();

            @Override
            public void onHeaders(Response response)
            {
                String content = response.headers().get("X-Content");
                if (content != null)
                    contentLength.set(Integer.parseInt(content));
            }

            @Override
            public void onContent(Response response, ByteBuffer content)
            {
                contentLength.addAndGet(-content.remaining());
            }

            @Override
            public void onComplete(Result result)
            {
                if (result.isFailed())
                    failures.add("Result failed " + result);
                if (contentLength.get() != 0)
                    failures.add("Content length mismatch " + contentLength);
                latch.countDown();
            }
        });
    }

    private class LoadHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            String method = request.getMethod().toUpperCase();
            switch (method)
            {
                case "GET":
                    int contentLength = request.getIntHeader("X-Download");
                    if (contentLength > 0)
                    {
                        response.setHeader("X-Content", String.valueOf(contentLength));
                        response.getOutputStream().write(new byte[contentLength]);
                    }
                    break;
                case "POST":
                    response.setHeader("X-Content", request.getHeader("X-Upload"));
                    IO.copy(request.getInputStream(), response.getOutputStream());
                    break;
            }

            if (Boolean.parseBoolean(request.getHeader("X-Close")))
                response.setHeader("Connection", "close");

            baseRequest.setHandled(true);
        }
    }
}
