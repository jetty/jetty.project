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

package org.eclipse.jetty.client;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Blocking;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientAsyncContentTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSmallAsyncContent(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response) throws Exception
            {
                try (Blocking.Callback blocker = _blocking.callback())
                {
                    response.write(false, blocker, "A");
                }
                try (Blocking.Callback blocker = _blocking.callback())
                {
                    response.write(false, blocker, "A");
                }
            }
        });

        AtomicInteger contentCount = new AtomicInteger();
        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        AtomicReference<CountDownLatch> contentLatch = new AtomicReference<>(new CountDownLatch(1));
        CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContentAsync((response, content, callback) ->
            {
                contentCount.incrementAndGet();
                callbackRef.set(callback);
                contentLatch.get().countDown();
            })
            .send(result -> completeLatch.countDown());

        assertTrue(contentLatch.get().await(5, TimeUnit.SECONDS));
        Callback callback = callbackRef.get();

        // Wait a while to be sure that the parsing does not proceed.
        TimeUnit.MILLISECONDS.sleep(1000);

        assertEquals(1, contentCount.get());

        // Succeed the content callback to proceed with parsing.
        callbackRef.set(null);
        contentLatch.set(new CountDownLatch(1));
        callback.succeeded();

        assertTrue(contentLatch.get().await(5, TimeUnit.SECONDS));
        callback = callbackRef.get();

        // Wait a while to be sure that the parsing does not proceed.
        TimeUnit.MILLISECONDS.sleep(1000);

        assertEquals(2, contentCount.get());
        assertEquals(1, completeLatch.getCount());

        // Succeed the content callback to proceed with parsing.
        callbackRef.set(null);
        contentLatch.set(new CountDownLatch(1));
        callback.succeeded();

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertEquals(2, contentCount.get());
    }

    // TODO
/*
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testConcurrentAsyncContent(Scenario scenario) throws Exception
    {
        AtomicReference<AsyncContext> asyncContextRef = new AtomicReference<>();
        startServer(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response) throws Exception
            {
                ServletOutputStream output = response.getOutputStream();
                output.write(new byte[1024]);
                output.flush();
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                asyncContextRef.set(asyncContext);
            }
        });
        AtomicReference<LongConsumer> demandRef = new AtomicReference<>();
        startClient(scenario, clientConnector -> new HttpClientTransportOverHTTP(clientConnector)
        {
            @Override
            public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
            {
                return customize(new HttpConnectionOverHTTP(endPoint, context)
                {
                    @Override
                    protected HttpChannelOverHTTP newHttpChannel()
                    {
                        return new HttpChannelOverHTTP(this)
                        {
                            @Override
                            protected HttpReceiverOverHTTP newHttpReceiver()
                            {
                                return new HttpReceiverOverHTTP(this)
                                {
                                    @Override
                                    public boolean content(ByteBuffer buffer)
                                    {
                                        try
                                        {
                                            boolean result = super.content(buffer);
                                            // The content has been notified, but the listener has not demanded.

                                            // Simulate an asynchronous demand from otherThread.
                                            // There is no further content, so otherThread will fill 0,
                                            // set the fill interest, and release the network buffer.
                                            CountDownLatch latch = new CountDownLatch(1);
                                            Thread otherThread = new Thread(() ->
                                            {
                                                demandRef.get().accept(1);
                                                latch.countDown();
                                            });
                                            otherThread.start();
                                            // Wait for otherThread to finish, then let this thread continue.
                                            assertTrue(latch.await(5, TimeUnit.SECONDS));

                                            return result;
                                        }
                                        catch (InterruptedException x)
                                        {
                                            throw new RuntimeException(x);
                                        }
                                    }
                                };
                            }
                        };
                    }
                }, context);
            }
        }, null);

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContentDemanded((response, demand, content, callback) ->
            {
                demandRef.set(demand);
                // Don't demand and don't succeed the callback.
            })
            .send(result ->
            {
                if (result.isSucceeded())
                    latch.countDown();
            });

        // Wait for the threads to finish their processing.
        Thread.sleep(1000);

        // Complete the response.
        asyncContextRef.get().complete();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
*/
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAsyncContentAbort(Scenario scenario) throws Exception
    {
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, callback, ByteBuffer.wrap(new byte[1024]));
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContentDemanded((response, demand, content, callback) -> response.abort(new Throwable()))
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAsyncGzipContentAbortThenDemand(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response) throws Exception
            {
                response.getHeaders().put("Content-Encoding", "gzip");
                OutputStream outputStream = Content.Sink.asOutputStream(response);
                GZIPOutputStream gzip = new GZIPOutputStream(outputStream);
                gzip.write(new byte[1024]);
                gzip.finish();
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContentDemanded((response, demand, content, callback) ->
            {
                response.abort(new Throwable());
                demand.accept(1);
            })
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAsyncGzipContentDelayedDemand(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response) throws Exception
            {
                response.getHeaders().put("Content-Encoding", "gzip");
                OutputStream outputStream = Content.Sink.asOutputStream(response);
                try (GZIPOutputStream gzip = new GZIPOutputStream(outputStream))
                {
                    gzip.write(new byte[1024]);
                }
            }
        });

        AtomicReference<LongConsumer> demandRef = new AtomicReference<>();
        CountDownLatch headersLatch = new CountDownLatch(1);
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContentDemanded(new Response.DemandedContentListener()
            {
                @Override
                public void onBeforeContent(Response response, LongConsumer demand)
                {
                    // Don't demand yet.
                    demandRef.set(demand);
                    headersLatch.countDown();
                }

                @Override
                public void onContent(Response response, LongConsumer demand, ByteBuffer content, Callback callback)
                {
                    demand.accept(1);
                }
            })
            .send(result ->
            {
                if (result.isSucceeded())
                    resultLatch.countDown();
            });

        assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        // Wait to make sure the demand is really delayed.
        Thread.sleep(500);
        demandRef.get().accept(1);

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    // TODO
/*
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAsyncGzipContentAbortWhileDecodingWithDelayedDemand(Scenario scenario) throws Exception
    {
        // Use a large content so that the gzip decoding is done in multiple passes.
        byte[] bytes = new byte[8 * 1024 * 1024];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos))
        {
            gzip.write(bytes);
        }
        byte[] gzipBytes = baos.toByteArray();
        int half = gzipBytes.length / 2;
        byte[] gzip1 = Arrays.copyOfRange(gzipBytes, 0, half);
        byte[] gzip2 = Arrays.copyOfRange(gzipBytes, half, gzipBytes.length);

        AtomicReference<AsyncContext> asyncContextRef = new AtomicReference<>();
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response) throws Exception
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                asyncContextRef.set(asyncContext);

                response.getHeaders().put("Content-Encoding", "gzip");
                ServletOutputStream output = response.getOutputStream();
                output.write(gzip1);
                output.flush();
            }
        });

        AtomicReference<LongConsumer> demandRef = new AtomicReference<>();
        CountDownLatch firstChunkLatch = new CountDownLatch(1);
        CountDownLatch secondChunkLatch = new CountDownLatch(1);
        CountDownLatch resultLatch = new CountDownLatch(1);
        AtomicInteger chunks = new AtomicInteger();
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContentDemanded((response, demand, content, callback) ->
            {
                if (chunks.incrementAndGet() == 1)
                {
                    try
                    {
                        // Don't demand, but make the server write the second chunk.
                        AsyncContext asyncContext = asyncContextRef.get();
                        asyncContext.getResponse().getOutputStream().write(gzip2);
                        asyncContext.complete();
                        demandRef.set(demand);
                        firstChunkLatch.countDown();
                    }
                    catch (IOException x)
                    {
                        throw new RuntimeException(x);
                    }
                }
                else
                {
                    response.abort(new Throwable());
                    demandRef.set(demand);
                    secondChunkLatch.countDown();
                }
            })
            .send(result ->
            {
                if (result.isFailed())
                    resultLatch.countDown();
            });

        assertTrue(firstChunkLatch.await(5, TimeUnit.SECONDS));
        // Wait to make sure the demand is really delayed.
        Thread.sleep(500);
        demandRef.get().accept(1);

        assertTrue(secondChunkLatch.await(5, TimeUnit.SECONDS));
        // Wait to make sure the demand is really delayed.
        Thread.sleep(500);
        demandRef.get().accept(1);

        assertTrue(resultLatch.await(555, TimeUnit.SECONDS));
    }
*/
}
