//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.eclipse.jetty.http.client.Transport.FCGI;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTimeoutsTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        // Skip FCGI for now, not much interested in its server-side behavior.
        Assumptions.assumeTrue(transport != FCGI);
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingReadWithDelayedFirstContentWithUndelayedDispatchIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        testBlockingReadWithDelayedFirstContentIdleTimeoutFires(scenario, false);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingReadWithDelayedFirstContentWithDelayedDispatchIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        testBlockingReadWithDelayedFirstContentIdleTimeoutFires(scenario, true);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncReadWithDelayedFirstContentWithUndelayedDispatchIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        testAsyncReadWithDelayedFirstContentIdleTimeoutFires(scenario, false);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncReadWithDelayedFirstContentWithDelayedDispatchIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        testAsyncReadWithDelayedFirstContentIdleTimeoutFires(scenario, true);
    }

    private void testBlockingReadWithDelayedFirstContentIdleTimeoutFires(TransportScenario scenario, boolean delayDispatch) throws Exception
    {
        testReadWithDelayedFirstContentIdleTimeoutFires(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // The client did not send the content,
                // idle timeout should result in IOException.
                request.getInputStream().read();
            }
        }, delayDispatch);
    }

    private void testAsyncReadWithDelayedFirstContentIdleTimeoutFires(TransportScenario scenario, boolean delayDispatch) throws Exception
    {
        testReadWithDelayedFirstContentIdleTimeoutFires(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {
                    }

                    @Override
                    public void onAllDataRead()
                    {
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        if (t instanceof TimeoutException)
                            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);

                        asyncContext.complete();
                    }
                });
            }
        }, delayDispatch);
    }

    private void testReadWithDelayedFirstContentIdleTimeoutFires(TransportScenario scenario, Handler handler, boolean delayDispatch) throws Exception
    {
        scenario.httpConfig.setDelayDispatchUntilContent(delayDispatch);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    handler.handle(target, jettyRequest, request, response);
                }
                finally
                {
                    handlerLatch.countDown();
                }
            }
        });
        long idleTimeout = 1000;
        scenario.setServerIdleTimeout(idleTimeout);

        CountDownLatch resultLatch = new CountDownLatch(2);
        DeferredContentProvider content = new DeferredContentProvider();
        scenario.client.POST(scenario.newURI())
            .content(content)
            .onResponseSuccess(response ->
            {
                if (response.getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                    resultLatch.countDown();
                content.close();
            })
            .send(result -> resultLatch.countDown());

        // The client did not send the content, the request was
        // dispatched, the server should have idle timed it out.
        assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testNoBlockingTimeoutBlockingReadIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        scenario.httpConfig.setBlockingTimeout(-1);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new BlockingReadHandler(handlerLatch));
        long idleTimeout = 2500;
        scenario.setServerIdleTimeout(idleTimeout);

        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            DeferredContentProvider contentProvider = new DeferredContentProvider(ByteBuffer.allocate(1));
            CountDownLatch resultLatch = new CountDownLatch(1);
            scenario.client.POST(scenario.newURI())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                        resultLatch.countDown();
                });

            // Blocking read should timeout.
            assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
            // Complete the request.
            contentProvider.close();
            assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingTimeoutSmallerThanIdleTimeoutBlockingReadBlockingTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        long blockingTimeout = 2500;
        scenario.httpConfig.setBlockingTimeout(blockingTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new BlockingReadHandler(handlerLatch));
        long idleTimeout = 3 * blockingTimeout;
        scenario.setServerIdleTimeout(idleTimeout);

        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            DeferredContentProvider contentProvider = new DeferredContentProvider(ByteBuffer.allocate(1));
            CountDownLatch resultLatch = new CountDownLatch(1);
            scenario.client.POST(scenario.newURI())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                        resultLatch.countDown();
                });

            // Blocking read should timeout.
            assertTrue(handlerLatch.await(2 * blockingTimeout, TimeUnit.MILLISECONDS));
            // Complete the request.
            contentProvider.close();
            assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingTimeoutLargerThanIdleTimeoutBlockingReadIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        long idleTimeout = 2500;
        long blockingTimeout = 3 * idleTimeout;
        scenario.httpConfig.setBlockingTimeout(blockingTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new BlockingReadHandler(handlerLatch));
        scenario.setServerIdleTimeout(idleTimeout);

        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            DeferredContentProvider contentProvider = new DeferredContentProvider(ByteBuffer.allocate(1));
            CountDownLatch resultLatch = new CountDownLatch(1);
            scenario.client.POST(scenario.newURI())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                        resultLatch.countDown();
                });

            // Blocking read should timeout.
            assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
            // Complete the request.
            contentProvider.close();
            assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testNoBlockingTimeoutBlockingWriteIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        scenario.httpConfig.setBlockingTimeout(-1);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new BlockingWriteHandler(handlerLatch));
        long idleTimeout = 2500;
        scenario.setServerIdleTimeout(idleTimeout);

        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            BlockingQueue<Callback> callbacks = new LinkedBlockingQueue<>();
            CountDownLatch resultLatch = new CountDownLatch(1);
            scenario.client.newRequest(scenario.newURI())
                .onResponseContentAsync((response, content, callback) ->
                {
                    // Do not succeed the callback so the server will block writing.
                    callbacks.offer(callback);
                })
                .send(result ->
                {
                    if (result.isFailed())
                        resultLatch.countDown();
                });

            // Blocking write should timeout.
            assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
            // After the server stopped sending, consume on the client to read the early EOF.
            while (true)
            {
                Callback callback = callbacks.poll(1, TimeUnit.SECONDS);
                if (callback == null)
                    break;
                callback.succeeded();
            }
            assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingTimeoutSmallerThanIdleTimeoutBlockingWriteBlockingTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        long blockingTimeout = 2500;
        scenario.httpConfig.setBlockingTimeout(blockingTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new BlockingWriteHandler(handlerLatch));
        long idleTimeout = 3 * blockingTimeout;
        scenario.setServerIdleTimeout(idleTimeout);

        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            BlockingQueue<Callback> callbacks = new LinkedBlockingQueue<>();
            CountDownLatch resultLatch = new CountDownLatch(1);
            scenario.client.newRequest(scenario.newURI())
                .onResponseContentAsync((response, content, callback) ->
                {
                    // Do not succeed the callback so the server will block writing.
                    callbacks.offer(callback);
                })
                .send(result ->
                {
                    if (result.isFailed())
                        resultLatch.countDown();
                });

            // Blocking write should timeout.
            assertTrue(handlerLatch.await(2 * blockingTimeout, TimeUnit.MILLISECONDS));
            // After the server stopped sending, consume on the client to read the early EOF.
            while (true)
            {
                Callback callback = callbacks.poll(1, TimeUnit.SECONDS);
                if (callback == null)
                    break;
                callback.succeeded();
            }
            assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingTimeoutLargerThanIdleTimeoutBlockingWriteIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        long idleTimeout = 2500;
        long blockingTimeout = 3 * idleTimeout;
        scenario.httpConfig.setBlockingTimeout(blockingTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new BlockingWriteHandler(handlerLatch));
        scenario.setServerIdleTimeout(idleTimeout);

        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            BlockingQueue<Callback> callbacks = new LinkedBlockingQueue<>();
            CountDownLatch resultLatch = new CountDownLatch(1);
            scenario.client.newRequest(scenario.newURI())
                .onResponseContentAsync((response, content, callback) ->
                {
                    // Do not succeed the callback so the server will block writing.
                    callbacks.offer(callback);
                })
                .send(result ->
                {
                    if (result.isFailed())
                        resultLatch.countDown();
                });

            // Blocking read should timeout.
            assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
            // After the server stopped sending, consume on the client to read the early EOF.
            while (true)
            {
                Callback callback = callbacks.poll(1, TimeUnit.SECONDS);
                if (callback == null)
                    break;
                callback.succeeded();
            }
            assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingTimeoutWithSlowRead(Transport transport) throws Exception
    {
        init(transport);
        long idleTimeout = 2500;
        long blockingTimeout = 2 * idleTimeout;
        scenario.httpConfig.setBlockingTimeout(blockingTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try
                {
                    baseRequest.setHandled(true);
                    ServletInputStream input = request.getInputStream();
                    while (true)
                    {
                        int read = input.read();
                        if (read < 0)
                            break;
                    }
                }
                catch (IOException x)
                {
                    handlerLatch.countDown();
                    throw x;
                }
            }
        });
        scenario.setServerIdleTimeout(idleTimeout);

        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            DeferredContentProvider contentProvider = new DeferredContentProvider();
            CountDownLatch resultLatch = new CountDownLatch(1);
            scenario.client.newRequest(scenario.newURI())
                .content(contentProvider)
                .send(result ->
                {
                    // Result may fail to send the whole request body,
                    // but the response has arrived successfully.
                    if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                        resultLatch.countDown();
                });

            // The writes should be slow but not trigger the idle timeout.
            long period = idleTimeout / 2;
            long writes = 2 * (blockingTimeout / period);
            for (long i = 0; i < writes; ++i)
            {
                contentProvider.offer(ByteBuffer.allocate(1));
                Thread.sleep(period);
            }
            contentProvider.close();

            // Blocking read should timeout.
            assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
            assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncReadIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                ServletInputStream input = request.getInputStream();
                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        assertEquals(0, input.read());
                        assertFalse(input.isReady());
                    }

                    @Override
                    public void onAllDataRead()
                    {
                    }

                    @Override
                    public void onError(Throwable failure)
                    {
                        if (failure instanceof TimeoutException)
                        {
                            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                            handlerLatch.countDown();
                        }

                        asyncContext.complete();
                    }
                });
            }
        });
        long idleTimeout = 2500;
        scenario.setServerIdleTimeout(idleTimeout);

        DeferredContentProvider contentProvider = new DeferredContentProvider(ByteBuffer.allocate(1));
        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.POST(scenario.newURI())
            .content(contentProvider)
            .send(result ->
            {
                if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                    resultLatch.countDown();
            });

        // Async read should timeout.
        assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // Complete the request.
        contentProvider.close();
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncWriteIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);

        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                ServletOutputStream output = response.getOutputStream();
                output.setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        if (output.isReady())
                            output.write(new byte[64 * 1024 * 1024]);
                    }

                    @Override
                    public void onError(Throwable failure)
                    {
                        if (failure instanceof TimeoutException)
                            handlerLatch.countDown();

                        asyncContext.complete();
                    }
                });
            }
        });
        long idleTimeout = 2500;
        scenario.setServerIdleTimeout(idleTimeout);

        BlockingQueue<Callback> callbacks = new LinkedBlockingQueue<>();
        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .onResponseContentAsync((response, content, callback) ->
            {
                // Do not succeed the callback so the server will block writing.
                callbacks.offer(callback);
            })
            .send(result ->
            {
                if (result.isFailed())
                    resultLatch.countDown();
            });

        // Async write should timeout.
        assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // After the server stopped sending, consume on the client to read the early EOF.
        while (true)
        {
            Callback callback = callbacks.poll(1, TimeUnit.SECONDS);
            if (callback == null)
                break;
            callback.succeeded();
        }
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingReadWithMinimumDataRateBelowLimit(Transport transport) throws Exception
    {
        init(transport);
        int bytesPerSecond = 20;
        scenario.requestLog.clear();
        scenario.httpConfig.setMinRequestDataRate(bytesPerSecond);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try
                {
                    baseRequest.setHandled(true);
                    ServletInputStream input = request.getInputStream();
                    while (true)
                    {
                        int read = input.read();
                        if (read < 0)
                            break;
                    }
                }
                catch (BadMessageException x)
                {
                    handlerLatch.countDown();
                    throw x;
                }
            }
        });

        DeferredContentProvider contentProvider = new DeferredContentProvider();
        BlockingQueue<Object> results = new BlockingArrayQueue<>();
        scenario.client.newRequest(scenario.newURI())
            .content(contentProvider)
            .send(result ->
            {
                if (result.isFailed())
                    results.offer(result.getFailure());
                else
                    results.offer(result.getResponse().getStatus());
            });

        for (int i = 0; i < 3; ++i)
        {
            contentProvider.offer(ByteBuffer.allocate(bytesPerSecond / 2));
            Thread.sleep(2500);
        }
        contentProvider.close();

        assertThat(scenario.requestLog.poll(5, TimeUnit.SECONDS), containsString(" 408"));

        // Request should timeout.
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));

        Object result = results.poll(5, TimeUnit.SECONDS);
        assertNotNull(result);
        if (result instanceof Integer)
            assertThat((Integer)result, is(408));
        else
            assertThat(result, instanceOf(Throwable.class));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingReadWithMinimumDataRateAboveLimit(Transport transport) throws Exception
    {
        init(transport);
        int bytesPerSecond = 20;
        scenario.httpConfig.setMinRequestDataRate(bytesPerSecond);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                ServletInputStream input = request.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }
                handlerLatch.countDown();
            }
        });

        DeferredContentProvider contentProvider = new DeferredContentProvider();
        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .content(contentProvider)
            .send(result ->
            {
                if (result.getResponse().getStatus() == HttpStatus.OK_200)
                    resultLatch.countDown();
            });

        for (int i = 0; i < 3; ++i)
        {
            contentProvider.offer(ByteBuffer.allocate(bytesPerSecond * 2));
            Thread.sleep(2500);
        }
        contentProvider.close();

        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingReadHttpIdleTimeoutOverridesIdleTimeout(Transport transport) throws Exception
    {
        init(transport);
        long httpIdleTimeout = 2500;
        long idleTimeout = 3 * httpIdleTimeout;
        scenario.httpConfig.setIdleTimeout(httpIdleTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new BlockingReadHandler(handlerLatch));
        scenario.setServerIdleTimeout(idleTimeout);

        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            DeferredContentProvider contentProvider = new DeferredContentProvider(ByteBuffer.allocate(1));
            CountDownLatch resultLatch = new CountDownLatch(1);
            scenario.client.POST(scenario.newURI())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                        resultLatch.countDown();
                });

            // Blocking read should timeout.
            assertTrue(handlerLatch.await(2 * httpIdleTimeout, TimeUnit.MILLISECONDS));
            // Complete the request.
            contentProvider.close();
            assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncReadHttpIdleTimeoutOverridesIdleTimeout(Transport transport) throws Exception
    {
        init(transport);
        long httpIdleTimeout = 2500;
        long idleTimeout = 3 * httpIdleTimeout;
        scenario.httpConfig.setIdleTimeout(httpIdleTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                ServletInputStream input = request.getInputStream();
                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        assertEquals(0, input.read());
                        assertFalse(input.isReady());
                    }

                    @Override
                    public void onAllDataRead()
                    {
                    }

                    @Override
                    public void onError(Throwable failure)
                    {
                        if (failure instanceof TimeoutException)
                        {
                            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                            handlerLatch.countDown();
                        }

                        asyncContext.complete();
                    }
                });
            }
        });
        scenario.setServerIdleTimeout(idleTimeout);

        DeferredContentProvider contentProvider = new DeferredContentProvider(ByteBuffer.allocate(1));
        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.POST(scenario.newURI())
            .content(contentProvider)
            .send(result ->
            {
                if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                    resultLatch.countDown();
            });

        // Async read should timeout.
        assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // Complete the request.
        contentProvider.close();
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testIdleTimeoutBeforeReadIsIgnored(Transport transport) throws Exception
    {
        init(transport);
        long idleTimeout = 1000;
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try
                {
                    Thread.sleep(idleTimeout + idleTimeout / 2);
                    IO.copy(request.getInputStream(), response.getOutputStream());
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });
        scenario.setServerIdleTimeout(idleTimeout);

        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        byte[] data1 = new byte[data.length / 2];
        System.arraycopy(data, 0, data1, 0, data1.length);
        byte[] data2 = new byte[data.length - data1.length];
        System.arraycopy(data, data1.length, data2, 0, data2.length);
        DeferredContentProvider content = new DeferredContentProvider(ByteBuffer.wrap(data1));
        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .path(scenario.servletPath)
            .content(content)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isSucceeded());
                    assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                    assertArrayEquals(data, getContent());
                    latch.countDown();
                }
            });

        // Wait for the server application to block reading.
        Thread.sleep(2 * idleTimeout);
        content.offer(ByteBuffer.wrap(data2));
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingWriteWithMinimumDataRateBelowLimit(Transport transport) throws Exception
    {
        // This test needs a large write to stall the server, and a slow reading client.
        // In HTTP/1.1, when using the loopback interface, the buffers are so large that
        // it would require a very large write (32 MiB) and a lot of time for this test
        // to pass. On the first writes, the server fills in the large buffers with a lot
        // of bytes (about 4 MiB), and so it would take a lot of time for the client to
        // read those bytes and eventually produce a write rate that will make the server
        // fail; and the write should be large enough to _not_ complete before the rate
        // is below the minimum.
        // In HTTP/2, we force the flow control window to be small, so that the server
        // stalls almost immediately without having written many bytes, so that the test
        // completes quickly.
        Assumptions.assumeTrue(transport.isHttp2Based());

        init(transport);

        int bytesPerSecond = 16 * 1024;
        scenario.httpConfig.setMinResponseDataRate(bytesPerSecond);
        CountDownLatch serverLatch = new CountDownLatch(1);
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                try
                {
                    ServletOutputStream output = response.getOutputStream();
                    output.write(new byte[8 * 1024 * 1024]);
                }
                catch (IOException x)
                {
                    serverLatch.countDown();
                }
            }
        });
        ((HttpClientTransportOverHTTP2)scenario.client.getTransport()).getHTTP2Client().setInitialStreamRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);

        // Setup the client to read slower than the min data rate.
        BlockingQueue<Object> objects = new LinkedBlockingQueue<>();
        CountDownLatch clientLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .onResponseContentAsync((response, content, callback) ->
            {
                objects.offer(content.remaining());
                objects.offer(callback);
            })
            .send(result ->
            {
                objects.offer(-1);
                objects.offer(Callback.NOOP);
                if (result.isFailed())
                    clientLatch.countDown();
            });

        long readRate = bytesPerSecond / 2;
        while (true)
        {
            int bytes = (Integer)objects.poll(5, TimeUnit.SECONDS);
            if (bytes < 0)
                break;
            long ms = bytes * 1000L / readRate;
            Thread.sleep(ms);
            Callback callback = (Callback)objects.poll();
            callback.succeeded();
        }

        assertTrue(serverLatch.await(15, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(15, TimeUnit.SECONDS));
    }

    private static class BlockingReadHandler extends AbstractHandler
    {
        private final CountDownLatch handlerLatch;

        public BlockingReadHandler(CountDownLatch handlerLatch)
        {
            this.handlerLatch = handlerLatch;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            baseRequest.setHandled(true);
            ServletInputStream input = request.getInputStream();
            assertEquals(0, input.read());
            try
            {
                input.read();
            }
            catch (IOException x)
            {
                handlerLatch.countDown();
                throw x;
            }
        }
    }

    private static class BlockingWriteHandler extends AbstractHandler
    {
        private final CountDownLatch handlerLatch;

        private BlockingWriteHandler(CountDownLatch handlerLatch)
        {
            this.handlerLatch = handlerLatch;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            baseRequest.setHandled(true);
            ServletOutputStream output = response.getOutputStream();
            try
            {
                output.write(new byte[64 * 1024 * 1024]);
            }
            catch (IOException x)
            {
                handlerLatch.countDown();
                throw x;
            }
        }
    }
}
