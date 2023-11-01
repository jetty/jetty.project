//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.test.client.transport;

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
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.BufferingResponseListener;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class ServerTimeoutsTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testBlockingReadWithDelayedFirstContentWithUndelayedDispatchIdleTimeoutFires(Transport transport) throws Exception
    {
        assumeTrue(transport != Transport.H3 && transport != Transport.H2C && transport != Transport.H2); // TODO Fix
        testBlockingReadWithDelayedFirstContentIdleTimeoutFires(transport, false);
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testBlockingReadWithDelayedFirstContentWithDelayedDispatchIdleTimeoutFires(Transport transport) throws Exception
    {
        assumeTrue(transport != Transport.H3 && transport != Transport.H2C && transport != Transport.H2); // TODO Fix
        testBlockingReadWithDelayedFirstContentIdleTimeoutFires(transport, true);
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testAsyncReadWithDelayedFirstContentWithUndelayedDispatchIdleTimeoutFires(Transport transport) throws Exception
    {
        testAsyncReadWithDelayedFirstContentIdleTimeoutFires(transport, false);
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testAsyncReadWithDelayedFirstContentWithDelayedDispatchIdleTimeoutFires(Transport transport) throws Exception
    {
        testAsyncReadWithDelayedFirstContentIdleTimeoutFires(transport, true);
    }

    private void testBlockingReadWithDelayedFirstContentIdleTimeoutFires(Transport transport, boolean delayDispatch) throws Exception
    {
        testReadWithDelayedFirstContentIdleTimeoutFires(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // The client did not send the content,
                // idle timeout should result in IOException.
                request.getInputStream().read();
            }
        }, delayDispatch);
    }

    private void testAsyncReadWithDelayedFirstContentIdleTimeoutFires(Transport transport, boolean delayDispatch) throws Exception
    {
        testReadWithDelayedFirstContentIdleTimeoutFires(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
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

    private void testReadWithDelayedFirstContentIdleTimeoutFires(Transport transport, HttpServlet servlet, boolean delayDispatch) throws Exception
    {
        httpConfig.setDelayDispatchUntilContent(delayDispatch);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                try
                {
                    servlet.service(request, response);
                }
                finally
                {
                    handlerLatch.countDown();
                }
            }
        });
        long idleTimeout = 1000;
        setStreamIdleTimeout(idleTimeout);

        CountDownLatch resultLatch = new CountDownLatch(2);
        AsyncRequestContent content = new AsyncRequestContent();
        client.POST(newURI(transport))
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
    @MethodSource("transportsNoFCGI")
    public void testAsyncReadIdleTimeoutFires(Transport transport) throws Exception
    {
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
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
        setStreamIdleTimeout(idleTimeout);

        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.allocate(1));
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.POST(newURI(transport))
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
    @MethodSource("transportsNoFCGI")
    public void testAsyncWriteIdleTimeoutFires(Transport transport) throws Exception
    {
        // TODO fix for h3
        assumeTrue(transport != Transport.H3);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
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
        setStreamIdleTimeout(idleTimeout);

        BlockingQueue<Runnable> demanders = new LinkedBlockingQueue<>();
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .onResponseContentAsync((response, chunk, demander) ->
            {
                // Do not succeed the callback so the server will block writing.
                demanders.offer(demander);
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
            Runnable demander = demanders.poll(1, TimeUnit.SECONDS);
            if (demander == null)
                break;
            demander.run();
        }
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testBlockingReadWithMinimumDataRateBelowLimit(Transport transport) throws Exception
    {
        int bytesPerSecond = 20;
        httpConfig.setMinRequestDataRate(bytesPerSecond);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try
                {
                    ServletInputStream input = request.getInputStream();
                    while (true)
                    {
                        int read = input.read();
                        if (read < 0)
                            break;
                    }
                }
                catch (Throwable x)
                {
                    if (x instanceof HttpException)
                        handlerLatch.countDown();
                    throw x;
                }
            }
        });

        AsyncRequestContent content = new AsyncRequestContent();
        AtomicReference<Response> responseRef = new AtomicReference<>();
        CountDownLatch responseLatch = new CountDownLatch(1);
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .body(content)
            .onResponseSuccess(response ->
            {
                responseRef.set(response);
                responseLatch.countDown();
                // Now that we have the response, fail the request,
                // as the request body has not been fully sent yet.
                response.abort(new Exception("thrown by the test"));
            })
            .send(result -> resultLatch.countDown());

        for (int i = 0; i < 3; ++i)
        {
            content.write(ByteBuffer.allocate(bytesPerSecond / 2), Callback.NOOP);
            Thread.sleep(2500);
        }
        content.close();

        // Request should timeout on server.
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.REQUEST_TIMEOUT_408, responseRef.get().getStatus());
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testBlockingReadWithMinimumDataRateAboveLimit(Transport transport) throws Exception
    {
        assumeTrue(transport != Transport.H3 && transport != Transport.H2C && transport != Transport.H2); // TODO Fix

        int bytesPerSecond = 20;
        httpConfig.setMinRequestDataRate(bytesPerSecond);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
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
        client.newRequest(newURI(transport))
            .body(content)
            .send(result ->
            {
                if (result.getResponse().getStatus() == HttpStatus.OK_200)
                    resultLatch.countDown();
            });

        for (int i = 0; i < 3; ++i)
        {
            content.write(ByteBuffer.allocate(bytesPerSecond * 2), Callback.NOOP);
            Thread.sleep(2500);
        }
        content.close();

        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testBlockingReadHttpIdleTimeoutOverridesIdleTimeout(Transport transport) throws Exception
    {
        long httpIdleTimeout = 2500;
        long idleTimeout = 3 * httpIdleTimeout;
        httpConfig.setIdleTimeout(httpIdleTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(transport, new BlockingReadServlet(handlerLatch));
        setStreamIdleTimeout(idleTimeout);

        try (StacklessLogging ignore = new StacklessLogging(HttpChannelState.class))
        {
            AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.allocate(1));
            CountDownLatch resultLatch = new CountDownLatch(1);
            client.POST(newURI(transport))
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
    @MethodSource("transportsNoFCGI")
    public void testAsyncReadHttpIdleTimeoutOverridesIdleTimeout(Transport transport) throws Exception
    {
        long httpIdleTimeout = 2000;
        long idleTimeout = 3 * httpIdleTimeout;
        httpConfig.setIdleTimeout(httpIdleTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                ServletInputStream input = request.getInputStream();
                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        while (input.isReady())
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
                            response.setStatus(HttpStatus.GATEWAY_TIMEOUT_504);
                            handlerLatch.countDown();
                        }

                        asyncContext.complete();
                    }
                });
            }
        });
        setStreamIdleTimeout(idleTimeout);

        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.allocate(1));
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.POST(newURI(transport))
            .body(content)
            .send(result ->
            {
                if (result.getResponse().getStatus() == HttpStatus.GATEWAY_TIMEOUT_504)
                    resultLatch.countDown();
            });

        // Async read should timeout.
        assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // Complete the request.
        content.close();
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @Disabled
    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testIdleTimeoutBeforeReadIsIgnored(Transport transport) throws Exception
    {
        long idleTimeout = 1000;
        start(transport, new HttpServlet()
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
        setStreamIdleTimeout(idleTimeout);

        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        byte[] data1 = new byte[data.length / 2];
        System.arraycopy(data, 0, data1, 0, data1.length);
        byte[] data2 = new byte[data.length - data1.length];
        System.arraycopy(data, data1.length, data2, 0, data2.length);
        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.wrap(data1));
        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
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
        content.write(ByteBuffer.wrap(data2), Callback.NOOP);
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Disabled
    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
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
        assumeTrue(transport == Transport.H2C || transport == Transport.H2);

        int bytesPerSecond = 16 * 1024;
        httpConfig.setMinResponseDataRate(bytesPerSecond);
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
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
        ((HttpClientTransportOverHTTP2)client.getTransport()).getHTTP2Client().setInitialStreamRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);

        // Setup the client to read slower than the min data rate.
        BlockingQueue<Object> objects = new LinkedBlockingQueue<>();
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .onResponseContentAsync((response, chunk, demander) ->
            {
                objects.offer(chunk.remaining());
                objects.offer(demander);
            })
            .send(result ->
            {
                objects.offer(-1);
                objects.offer((Runnable)() -> {});
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
            Runnable demander = (Runnable)objects.poll();
            demander.run();
        }

        assertTrue(serverLatch.await(15, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(15, TimeUnit.SECONDS));
    }

    private static class BlockingReadServlet extends HttpServlet
    {
        private final CountDownLatch handlerLatch;

        public BlockingReadServlet(CountDownLatch handlerLatch)
        {
            this.handlerLatch = handlerLatch;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            ServletInputStream input = request.getInputStream();
            assertEquals(0, input.read());
            IOException x = assertThrows(IOException.class, input::read);
            handlerLatch.countDown();
            throw x;
        }
    }
}
