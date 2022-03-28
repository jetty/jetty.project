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

package org.eclipse.jetty.ee10.http.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.eclipse.jetty.ee10.http.client.Transport.FCGI;
import static org.eclipse.jetty.ee10.http.client.Transport.H2;
import static org.eclipse.jetty.ee10.http.client.Transport.H2C;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        scenario.setRequestIdleTimeout(idleTimeout);

        CountDownLatch resultLatch = new CountDownLatch(2);
        AsyncRequestContent content = new AsyncRequestContent();
        scenario.client.POST(scenario.newURI())
            .body(content)
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
        scenario.setRequestIdleTimeout(idleTimeout);

        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.allocate(1));
        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.POST(scenario.newURI())
            .body(content)
            .send(result ->
            {
                if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                    resultLatch.countDown();
            });

        // Async read should timeout.
        assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // Complete the request.
        content.close();
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
        scenario.setRequestIdleTimeout(idleTimeout);

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

        AsyncRequestContent content = new AsyncRequestContent();
        AtomicReference<Response> responseRef = new AtomicReference<>();
        CountDownLatch responseLatch = new CountDownLatch(1);
        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .body(content)
            .onResponseSuccess(response ->
            {
                responseRef.set(response);
                responseLatch.countDown();
                // Now that we have the response, fail the request,
                // as the request body has not been fully sent yet.
                response.abort(new Exception("thrown by the test"));
            })
            .send(result ->
            {
                resultLatch.countDown();
            });

        for (int i = 0; i < 3; ++i)
        {
            content.offer(ByteBuffer.allocate(bytesPerSecond / 2));
            Thread.sleep(2500);
        }
        content.close();

        assertThat(scenario.requestLog.poll(5, TimeUnit.SECONDS), containsString(" 408"));

        // Request should timeout on server.
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.REQUEST_TIMEOUT_408, responseRef.get().getStatus());
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
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

        AsyncRequestContent content = new AsyncRequestContent();
        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .body(content)
            .send(result ->
            {
                if (result.getResponse().getStatus() == HttpStatus.OK_200)
                    resultLatch.countDown();
            });

        for (int i = 0; i < 3; ++i)
        {
            content.offer(ByteBuffer.allocate(bytesPerSecond * 2));
            Thread.sleep(2500);
        }
        content.close();

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
        scenario.setRequestIdleTimeout(idleTimeout);

        try (StacklessLogging ignore = new StacklessLogging(HttpChannelState.class))
        {
            AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.allocate(1));
            CountDownLatch resultLatch = new CountDownLatch(1);
            scenario.client.POST(scenario.newURI())
                .body(content)
                .send(result ->
                {
                    if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                        resultLatch.countDown();
                });

            // Blocking read should timeout.
            assertTrue(handlerLatch.await(2 * httpIdleTimeout, TimeUnit.MILLISECONDS));
            // Complete the request.
            content.close();
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
        scenario.setRequestIdleTimeout(idleTimeout);

        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.allocate(1));
        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.POST(scenario.newURI())
            .body(content)
            .send(result ->
            {
                if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                    resultLatch.countDown();
            });

        // Async read should timeout.
        assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // Complete the request.
        content.close();
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
        scenario.setRequestIdleTimeout(idleTimeout);

        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        byte[] data1 = new byte[data.length / 2];
        System.arraycopy(data, 0, data1, 0, data1.length);
        byte[] data2 = new byte[data.length - data1.length];
        System.arraycopy(data, data1.length, data2, 0, data2.length);
        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.wrap(data1));
        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .path(scenario.servletPath)
            .body(content)
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
        Assumptions.assumeTrue(transport == H2C || transport == H2);

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
}
