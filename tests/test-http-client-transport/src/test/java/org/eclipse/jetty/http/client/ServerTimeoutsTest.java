//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.http.client.AbstractTest.Transport;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class ServerTimeoutsTest extends AbstractTest
{
    public ServerTimeoutsTest(Transport transport)
    {
        // Skip FCGI for now, not much interested in its server-side behavior.
        super(transport == Transport.FCGI ? null : transport);
    }

    private void setServerIdleTimeout(long idleTimeout)
    {
        AbstractHTTP2ServerConnectionFactory h2 = connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class);
        if (h2 != null)
            h2.setStreamIdleTimeout(idleTimeout);
        else
            if (connector instanceof AbstractConnector)
                AbstractConnector.class.cast(connector).setIdleTimeout(idleTimeout);
    }

    @Test
    public void testBlockingReadWithDelayedFirstContentWithUndelayedDispatchIdleTimeoutFires() throws Exception
    {
        testBlockingReadWithDelayedFirstContentIdleTimeoutFires(false);
    }

    @Test
    public void testBlockingReadWithDelayedFirstContentWithDelayedDispatchIdleTimeoutFires() throws Exception
    {
        testBlockingReadWithDelayedFirstContentIdleTimeoutFires(true);
    }

    @Test
    public void testAsyncReadWithDelayedFirstContentWithUndelayedDispatchIdleTimeoutFires() throws Exception
    {
        testAsyncReadWithDelayedFirstContentIdleTimeoutFires(false);
    }

    @Test
    public void testAsyncReadWithDelayedFirstContentWithDelayedDispatchIdleTimeoutFires() throws Exception
    {
        testAsyncReadWithDelayedFirstContentIdleTimeoutFires(true);
    }

    private void testBlockingReadWithDelayedFirstContentIdleTimeoutFires(boolean delayDispatch) throws Exception
    {
        testReadWithDelayedFirstContentIdleTimeoutFires(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                // The client did not send the content,
                // idle timeout should result in IOException.
                request.getInputStream().read();
            }
        }, delayDispatch);
    }

    private void testAsyncReadWithDelayedFirstContentIdleTimeoutFires(boolean delayDispatch) throws Exception
    {
        testReadWithDelayedFirstContentIdleTimeoutFires(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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

    private void testReadWithDelayedFirstContentIdleTimeoutFires(Handler handler, boolean delayDispatch) throws Exception
    {
        httpConfig.setDelayDispatchUntilContent(delayDispatch);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new AbstractHandler()
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
        setServerIdleTimeout(idleTimeout);

        CountDownLatch resultLatch = new CountDownLatch(2);
        DeferredContentProvider content = new DeferredContentProvider();
        client.POST(newURI())
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
        Assert.assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testNoBlockingTimeoutBlockingReadIdleTimeoutFires() throws Exception
    {
        httpConfig.setBlockingTimeout(-1);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new BlockingReadHandler(handlerLatch));
        long idleTimeout = 2500;
        setServerIdleTimeout(idleTimeout);

        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            DeferredContentProvider contentProvider = new DeferredContentProvider(ByteBuffer.allocate(1));
            CountDownLatch resultLatch = new CountDownLatch(1);
            client.POST(newURI())
                    .content(contentProvider)
                    .send(result ->
                    {
                        if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                            resultLatch.countDown();
                    });

            // Blocking read should timeout.
            Assert.assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
            // Complete the request.
            contentProvider.close();
            Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testBlockingTimeoutSmallerThanIdleTimeoutBlockingReadBlockingTimeoutFires() throws Exception
    {
        long blockingTimeout = 2500;
        httpConfig.setBlockingTimeout(blockingTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new BlockingReadHandler(handlerLatch));
        long idleTimeout = 3 * blockingTimeout;
        setServerIdleTimeout(idleTimeout);

        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            DeferredContentProvider contentProvider = new DeferredContentProvider(ByteBuffer.allocate(1));
            CountDownLatch resultLatch = new CountDownLatch(1);
            client.POST(newURI())
                    .content(contentProvider)
                    .send(result ->
                    {
                        if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                            resultLatch.countDown();
                    });

            // Blocking read should timeout.
            Assert.assertTrue(handlerLatch.await(2 * blockingTimeout, TimeUnit.MILLISECONDS));
            // Complete the request.
            contentProvider.close();
            Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testBlockingTimeoutLargerThanIdleTimeoutBlockingReadIdleTimeoutFires() throws Exception
    {
        long idleTimeout = 2500;
        long blockingTimeout = 3 * idleTimeout;
        httpConfig.setBlockingTimeout(blockingTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new BlockingReadHandler(handlerLatch));
        setServerIdleTimeout(idleTimeout);

        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            DeferredContentProvider contentProvider = new DeferredContentProvider(ByteBuffer.allocate(1));
            CountDownLatch resultLatch = new CountDownLatch(1);
            client.POST(newURI())
                    .content(contentProvider)
                    .send(result ->
                    {
                        if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                            resultLatch.countDown();
                    });

            // Blocking read should timeout.
            Assert.assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
            // Complete the request.
            contentProvider.close();
            Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testNoBlockingTimeoutBlockingWriteIdleTimeoutFires() throws Exception
    {
        httpConfig.setBlockingTimeout(-1);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new BlockingWriteHandler(handlerLatch));
        long idleTimeout = 2500;
        setServerIdleTimeout(idleTimeout);

        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            BlockingQueue<Callback> callbacks = new LinkedBlockingQueue<>();
            CountDownLatch resultLatch = new CountDownLatch(1);
            client.newRequest(newURI())
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
            Assert.assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
            // After the server stopped sending, consume on the client to read the early EOF.
            while (true)
            {
                Callback callback = callbacks.poll(1, TimeUnit.SECONDS);
                if (callback == null)
                    break;
                callback.succeeded();
            }
            Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testBlockingTimeoutSmallerThanIdleTimeoutBlockingWriteBlockingTimeoutFires() throws Exception
    {
        long blockingTimeout = 2500;
        httpConfig.setBlockingTimeout(blockingTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new BlockingWriteHandler(handlerLatch));
        long idleTimeout = 3 * blockingTimeout;
        setServerIdleTimeout(idleTimeout);

        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            BlockingQueue<Callback> callbacks = new LinkedBlockingQueue<>();
            CountDownLatch resultLatch = new CountDownLatch(1);
            client.newRequest(newURI())
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
            Assert.assertTrue(handlerLatch.await(2 * blockingTimeout, TimeUnit.MILLISECONDS));
            // After the server stopped sending, consume on the client to read the early EOF.
            while (true)
            {
                Callback callback = callbacks.poll(1, TimeUnit.SECONDS);
                if (callback == null)
                    break;
                callback.succeeded();
            }
            Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testBlockingTimeoutLargerThanIdleTimeoutBlockingWriteIdleTimeoutFires() throws Exception
    {
        long idleTimeout = 2500;
        long blockingTimeout = 3 * idleTimeout;
        httpConfig.setBlockingTimeout(blockingTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new BlockingWriteHandler(handlerLatch));
        setServerIdleTimeout(idleTimeout);

        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            BlockingQueue<Callback> callbacks = new LinkedBlockingQueue<>();
            CountDownLatch resultLatch = new CountDownLatch(1);
            client.newRequest(newURI())
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
            Assert.assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
            // After the server stopped sending, consume on the client to read the early EOF.
            while (true)
            {
                Callback callback = callbacks.poll(1, TimeUnit.SECONDS);
                if (callback == null)
                    break;
                callback.succeeded();
            }
            Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testBlockingTimeoutWithSlowRead() throws Exception
    {
        long idleTimeout = 2500;
        long blockingTimeout = 2 * idleTimeout;
        httpConfig.setBlockingTimeout(blockingTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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
        setServerIdleTimeout(idleTimeout);

        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class, QueuedThreadPool.class))
        {
            DeferredContentProvider contentProvider = new DeferredContentProvider();
            CountDownLatch resultLatch = new CountDownLatch(1);
            client.newRequest(newURI())
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
            Assert.assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
            Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAsyncReadIdleTimeoutFires() throws Exception
    {
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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
                        Assert.assertEquals(0, input.read());
                        Assert.assertFalse(input.isReady());
                    }

                    @Override
                    public void onAllDataRead() throws IOException
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
        setServerIdleTimeout(idleTimeout);

        DeferredContentProvider contentProvider = new DeferredContentProvider(ByteBuffer.allocate(1));
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.POST(newURI())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                        resultLatch.countDown();
                });

        // Async read should timeout.
        Assert.assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // Complete the request.
        contentProvider.close();
        Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAsyncWriteIdleTimeoutFires() throws Exception
    {   
        // TODO work out why this test fails for UNIX_SOCKET
        Assume.assumeFalse(transport==Transport.UNIX_SOCKET);

        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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
        setServerIdleTimeout(idleTimeout);

        BlockingQueue<Callback> callbacks = new LinkedBlockingQueue<>();
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI())
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
        Assert.assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // After the server stopped sending, consume on the client to read the early EOF.
        while (true)
        {
            Callback callback = callbacks.poll(1, TimeUnit.SECONDS);
            if (callback == null)
                break;
            callback.succeeded();
        }
        Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testBlockingReadWithMinimumDataRateBelowLimit() throws Exception
    {
        int bytesPerSecond = 20;
        httpConfig.setMinRequestDataRate(bytesPerSecond);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.getResponse().getStatus() == HttpStatus.REQUEST_TIMEOUT_408)
                        resultLatch.countDown();
                });

        for (int i = 0; i < 3; ++i)
        {
            contentProvider.offer(ByteBuffer.allocate(bytesPerSecond / 2));
            Thread.sleep(2500);
        }
        contentProvider.close();

        // Request should timeout.
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testBlockingReadWithMinimumDataRateAboveLimit() throws Exception
    {
        int bytesPerSecond = 20;
        httpConfig.setMinRequestDataRate(bytesPerSecond);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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
        client.newRequest(newURI())
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

        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testBlockingReadHttpIdleTimeoutOverridesIdleTimeout() throws Exception
    {
        long httpIdleTimeout = 2500;
        long idleTimeout = 3 * httpIdleTimeout;
        httpConfig.setIdleTimeout(httpIdleTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new BlockingReadHandler(handlerLatch));
        setServerIdleTimeout(idleTimeout);

        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            DeferredContentProvider contentProvider = new DeferredContentProvider(ByteBuffer.allocate(1));
            CountDownLatch resultLatch = new CountDownLatch(1);
            client.POST(newURI())
                    .content(contentProvider)
                    .send(result ->
                    {
                        if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                            resultLatch.countDown();
                    });

            // Blocking read should timeout.
            Assert.assertTrue(handlerLatch.await(2 * httpIdleTimeout, TimeUnit.MILLISECONDS));
            // Complete the request.
            contentProvider.close();
            Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAsyncReadHttpIdleTimeoutOverridesIdleTimeout() throws Exception
    {
        long httpIdleTimeout = 2500;
        long idleTimeout = 3 * httpIdleTimeout;
        httpConfig.setIdleTimeout(httpIdleTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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
                        Assert.assertEquals(0, input.read());
                        Assert.assertFalse(input.isReady());
                    }

                    @Override
                    public void onAllDataRead() throws IOException
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
        setServerIdleTimeout(idleTimeout);

        DeferredContentProvider contentProvider = new DeferredContentProvider(ByteBuffer.allocate(1));
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.POST(newURI())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                        resultLatch.countDown();
                });

        // Async read should timeout.
        Assert.assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // Complete the request.
        contentProvider.close();
        Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testIdleTimeoutBeforeReadIsIgnored() throws Exception
    {
        long idleTimeout = 1000;
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
        setServerIdleTimeout(idleTimeout);

        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        byte[] data1 = new byte[data.length / 2];
        System.arraycopy(data, 0, data1, 0, data1.length);
        byte[] data2 = new byte[data.length - data1.length];
        System.arraycopy(data, data1.length, data2, 0, data2.length);
        DeferredContentProvider content = new DeferredContentProvider(ByteBuffer.wrap(data1));
        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI())
                .path(servletPath)
                .content(content)
                .send(new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isSucceeded());
                        Assert.assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                        Assert.assertArrayEquals(data, getContent());
                        latch.countDown();
                    }
                });

        // Wait for the server application to block reading.
        Thread.sleep(2 * idleTimeout);
        content.offer(ByteBuffer.wrap(data2));
        content.close();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testBlockingWriteWithMinimumDataRateBelowLimit() throws Exception
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
        Assume.assumeThat(transport, Matchers.isOneOf(Transport.H2, Transport.H2C));

        int bytesPerSecond = 16 * 1024;
        httpConfig.setMinResponseDataRate(bytesPerSecond);
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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
        client.newRequest(newURI())
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

        Assert.assertTrue(serverLatch.await(15, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(15, TimeUnit.SECONDS));
    }

    private static class BlockingReadHandler extends AbstractHandler.ErrorDispatchHandler
    {
        private final CountDownLatch handlerLatch;

        public BlockingReadHandler(CountDownLatch handlerLatch)
        {
            this.handlerLatch = handlerLatch;
        }

        @Override
        protected void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            ServletInputStream input = request.getInputStream();
            Assert.assertEquals(0, input.read());
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
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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
