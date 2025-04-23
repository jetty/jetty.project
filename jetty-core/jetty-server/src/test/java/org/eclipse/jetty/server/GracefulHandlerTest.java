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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GracefulHandlerTest
{
    private static final Logger LOG = LoggerFactory.getLogger(GracefulHandlerTest.class);
    private Server server;

    public Server createServer(Handler handler) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server, 1, 1);
        connector.setIdleTimeout(10000);
        connector.setShutdownIdleTimeout(1000);
        connector.setPort(0);
        server.addConnector(connector);

        server.setHandler(handler);
        return server;
    }

    @AfterEach
    public void teardown()
    {
        // cleanup any unstopped servers (due to test failure)
        LifeCycle.stop(server);
    }

    private Socket newSocketToServer(String id) throws IOException
    {
        URI serverURI = server.getURI();
        Socket socket = new Socket(serverURI.getHost(), serverURI.getPort());
        LOG.debug("{} : l={},r={}", id, socket.getLocalSocketAddress(), socket.getRemoteSocketAddress());
        return socket;
    }

    private CompletableFuture<Long> runAsyncServerStop()
    {
        CompletableFuture<Long> stopFuture = new CompletableFuture<>();

        CompletableFuture.runAsync(() ->
        {
            // Graceful shutdown + stop
            try (StacklessLogging ignore = new StacklessLogging(Response.class))
            {
                // Trigger graceful stop
                long beginStop = NanoTime.now();
                try
                {
                    server.stop();
                    long duration = NanoTime.millisSince(beginStop);
                    stopFuture.complete(duration);
                }
                catch (Throwable t)
                {
                    server.dumpStdErr();
                    stopFuture.completeExceptionally(t);
                }
            }
        });

        return stopFuture;
    }

    /**
     * Test for when a Handler throws an unhandled Exception from {@link Handler#handle(Request, Response, Callback)}
     * when in normal mode (not during graceful mode).  This test exists to ensure that the Callback management of
     * the {@link GracefulHandler} doesn't mess with normal operations of requests.
     */
    @Test
    public void testHandlerNormalUnhandledException() throws Exception
    {
        GracefulHandler gracefulHandler = new GracefulHandler();
        gracefulHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                throw new RuntimeException("Intentional Exception");
            }
        });
        server = createServer(gracefulHandler);
        server.setStopTimeout(10000);
        server.start();

        String rawRequest = """
            POST /?hint=intentional_failure HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            1234567890""";

        Socket client0 = newSocketToServer("client0");
        OutputStream output0 = client0.getOutputStream();
        InputStream input0 = client0.getInputStream();

        try (StacklessLogging ignore = new StacklessLogging(Response.class))
        {
            // Intentional Failure request
            output0.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            output0.flush();

            HttpTester.Response response = HttpTester.parseResponse(input0);
            assertNotNull(response);
            assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
            assertThat(response.get(HttpHeader.CONNECTION), is(nullValue()));
        }

        // Perform Stop
        CompletableFuture<Long> stopFuture = runAsyncServerStop();
        long stopDuration = stopFuture.get();
        assertThat(stopDuration, lessThan(5000L));

        // Ensure client connection is closed
        long beginClose = NanoTime.now();
        // The socket should have been closed
        assertThat(client0 + " not closed", input0.read(), is(-1));
        assertThat(client0 + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));
    }

    /**
     * Test for when a Handler throws an unhandled Exception {@link Handler#handle(Request, Response, Callback)}
     * when in graceful mode.
     */
    @Test
    public void testHandlerGracefulUnhandledException() throws Exception
    {
        CountDownLatch dispatchLatch = new CountDownLatch(1);
        GracefulHandler gracefulHandler = new GracefulHandler();
        gracefulHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                LOG.info("process: request={}", request);
                // let main thread know that we've reach this handler
                dispatchLatch.countDown();
                // now wait for graceful stop to begin
                await().atMost(5, TimeUnit.SECONDS).until(() -> gracefulHandler.isShutdown());
                throw new RuntimeException("Intentional Failure");
            }
        });
        server = createServer(gracefulHandler);
        server.setStopTimeout(10000);
        server.start();

        String rawRequest = """
            POST /?hint=intentional_failure HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            1234567890""";

        Socket client0 = newSocketToServer("client0");
        OutputStream output0 = client0.getOutputStream();
        InputStream input0 = client0.getInputStream();

        // Write request
        output0.write(rawRequest.getBytes(StandardCharsets.UTF_8));
        output0.flush();

        // Wait for request to reach handler
        assertTrue(dispatchLatch.await(3, TimeUnit.SECONDS), "Request didn't reach handler");

        // Perform stop
        CompletableFuture<Long> stopFuture = runAsyncServerStop();

        // Verify response
        HttpTester.Response response = HttpTester.parseResponse(input0);
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.get(HttpHeader.CONNECTION), is("close"));

        // Verify Stop duration
        long stopDuration = stopFuture.get();
        assertThat(stopDuration, lessThan(5000L));

        // Ensure client connection is closed
        long beginClose = NanoTime.now();
        // The socket should have been closed
        assertThat(client0 + " not closed", input0.read(), is(-1));
        assertThat(client0 + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));
    }

    /**
     * Test for when a Handler uses {@link Callback#failed(Throwable)} when in normal mode (not during graceful mode).
     * This test exists to ensure that the Callback management of the {@link GracefulHandler} doesn't
     * mess with normal operations of requests.
     */
    @Test
    public void testHandlerNormalCallbackFailure() throws Exception
    {
        GracefulHandler gracefulHandler = new GracefulHandler();
        gracefulHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                callback.failed(new RuntimeException("Intentional Failure"));
                return true;
            }
        });
        server = createServer(gracefulHandler);
        server.setStopTimeout(10000);
        server.start();

        String rawRequest = """
            POST /?hint=intentional_failure HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            1234567890""";

        Socket client0 = newSocketToServer("client0");
        OutputStream output0 = client0.getOutputStream();
        InputStream input0 = client0.getInputStream();

        try (StacklessLogging ignore = new StacklessLogging(Response.class))
        {
            // Write request
            output0.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            output0.flush();

            // Verify response
            HttpTester.Response response = HttpTester.parseResponse(input0);
            assertNotNull(response);
            assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
            assertThat(response.get(HttpHeader.CONNECTION), is(nullValue()));
        }

        // Perform stop
        CompletableFuture<Long> stopFuture = runAsyncServerStop();
        long stopDuration = stopFuture.get();
        assertThat(stopDuration, lessThan(5000L));

        // Ensure client connection is closed
        long beginClose = NanoTime.now();
        // The socket should have been closed
        assertThat(client0 + " not closed", input0.read(), is(-1));
        assertThat(client0 + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));
    }

    /**
     * Test for when a Handler uses {@link Callback#failed(Throwable)} when in graceful mode.
     */
    @Test
    public void testHandlerGracefulCallbackFailure() throws Exception
    {
        CountDownLatch dispatchLatch = new CountDownLatch(1);
        GracefulHandler gracefulHandler = new GracefulHandler();
        gracefulHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                dispatchLatch.countDown();
                // wait for graceful to kick in
                await().atMost(5, TimeUnit.SECONDS).until(() -> gracefulHandler.isShutdown());
                callback.failed(new RuntimeException("Intentional Failure"));
                return true;
            }
        });
        server = createServer(gracefulHandler);
        server.setStopTimeout(10000);
        server.start();

        String rawRequest = """
            POST /?hint=intentional_failure HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            1234567890""";

        Socket client0 = newSocketToServer("client0");
        OutputStream output0 = client0.getOutputStream();
        InputStream input0 = client0.getInputStream();

        // Intentional Failure request
        output0.write(rawRequest.getBytes(StandardCharsets.UTF_8));
        output0.flush();

        // Wait for request to reach handler
        assertTrue(dispatchLatch.await(3, TimeUnit.SECONDS), "Request didn't reach handler");

        // Perform stop
        CompletableFuture<Long> stopFuture = runAsyncServerStop();

        // Verify response
        HttpTester.Response response = HttpTester.parseResponse(input0);
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.get(HttpHeader.CONNECTION), is("close"));

        // Verify stop
        long stopDuration = stopFuture.get();
        assertThat(stopDuration, lessThan(5000L));

        // Ensure client connection is closed
        long beginClose = NanoTime.now();
        // The socket should have been closed
        assertThat(client0 + " not closed", input0.read(), is(-1));
        assertThat(client0 + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));
    }

    /**
     * Test for when a Handler returns false from {@link Handler#handle(Request, Response, Callback)}
     * when in normal mode (not during graceful mode).
     * This test exists to ensure that the Callback management of the {@link GracefulHandler} doesn't
     * mess with normal operations of requests.
     */
    @Test
    public void testHandlerNormalHandleReturnsFalse() throws Exception
    {
        GracefulHandler gracefulHandler = new GracefulHandler();
        gracefulHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                return false;
            }
        });
        server = createServer(gracefulHandler);
        server.setStopTimeout(10000);
        server.start();

        String rawRequest = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            \r
            """;

        Socket client0 = newSocketToServer("client0");
        OutputStream output0 = client0.getOutputStream();
        InputStream input0 = client0.getInputStream();

        // Intentional Failure request
        output0.write(rawRequest.getBytes(StandardCharsets.UTF_8));
        output0.flush();

        HttpTester.Response response = HttpTester.parseResponse(input0);
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(response.get(HttpHeader.CONNECTION), is(nullValue()));

        // Verify Stop duration
        CompletableFuture<Long> stopFuture = runAsyncServerStop();
        long stopDuration = stopFuture.get();
        assertThat(stopDuration, lessThan(5000L));

        // Ensure client connection is closed
        long beginClose = NanoTime.now();
        // The socket should have been closed
        assertThat(client0 + " not closed", input0.read(), is(-1));
        assertThat(client0 + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));
    }

    /**
     * Test for when a Handler returns false from {@link Handler#handle(Request, Response, Callback)}
     * when in graceful mode.
     */
    @Test
    public void testHandlerGracefulHandleReturnsFalse() throws Exception
    {
        AtomicReference<CompletableFuture<Long>> stopFuture = new AtomicReference<>();
        GracefulHandler gracefulHandler = new GracefulHandler();
        gracefulHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                stopFuture.set(runAsyncServerStop());
                await().atMost(5, TimeUnit.SECONDS).until(() -> gracefulHandler.isShutdown());
                return false;
            }
        });
        server = createServer(gracefulHandler);
        server.setStopTimeout(10000);
        server.start();

        String rawRequest = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            12345""";

        Socket client0 = newSocketToServer("client0");
        OutputStream output0 = client0.getOutputStream();
        InputStream input0 = client0.getInputStream();

        // Intentional Failure request
        output0.write(rawRequest.getBytes(StandardCharsets.UTF_8));
        output0.flush();

        HttpTester.Response response = HttpTester.parseResponse(input0);
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(response.get(HttpHeader.CONNECTION), is("close"));

        // Verify Stop duration
        long stopDuration = stopFuture.get().get();
        assertThat(stopDuration, lessThan(5000L));

        // Ensure client connection is closed
        long beginClose = NanoTime.now();
        // The socket should have been closed
        assertThat(client0 + " not closed", input0.read(), is(-1));
        assertThat(client0 + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));
    }

    /**
     * Test for behavior where the Handler is actively processing
     * a request when the server goes into graceful mode.
     */
    @Test
    public void testHandlerGracefulBlocked() throws Exception
    {
        CountDownLatch dispatchedToHandlerLatch = new CountDownLatch(1);
        GracefulHandler gracefulHandler = new GracefulHandler();
        gracefulHandler.setHandler(new BlockingReadHandler()
        {
            @Override
            protected void onBeforeRead(Request request, Response response)
            {
                dispatchedToHandlerLatch.countDown();
            }
        });
        server = createServer(gracefulHandler);
        server.setStopTimeout(10000);
        server.start();

        // Body is incomplete (send 5 bytes out of 10)
        String rawRequest = """
            POST /?hint=incomplete_body HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            12345""";

        Socket client0 = newSocketToServer("client0");
        OutputStream output0 = client0.getOutputStream();
        InputStream input0 = client0.getInputStream();

        // Write incomplete request
        output0.write(rawRequest.getBytes(StandardCharsets.UTF_8));
        output0.flush();

        // Wait for request to reach handler
        assertTrue(dispatchedToHandlerLatch.await(2, TimeUnit.SECONDS), "Request didn't reach handler");

        // Trigger stop
        CompletableFuture<Long> stopFuture = runAsyncServerStop();

        // Wait till we enter graceful mode
        await().atMost(5, TimeUnit.SECONDS).until(() -> gracefulHandler.isShutdown());

        // Send rest of data
        output0.write("67890".getBytes(StandardCharsets.UTF_8));
        output0.flush();

        // Test response
        HttpTester.Response response = HttpTester.parseResponse(input0);
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(HttpHeader.CONNECTION), is("close"));
        assertThat(response.getContent(), is("(Read:10) (Content-Length:10)"));

        // Verify Stop duration
        long stopDuration = stopFuture.get();
        assertThat(stopDuration, lessThan(5000L));

        // Ensure client connection is closed
        long beginClose = NanoTime.now();
        // The socket should have been closed
        assertThat(client0 + " should have been closed", input0.read(), is(-1));
        assertThat(client0 + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));
    }

    /**
     * Test for behavior where the Handler is actively processing
     * a request when the server goes into graceful mode.
     * <p>
     *     This variation has the response already committed
     *     when the server enters Graceful stop mode.
     * </p>
     */
    @Test
    public void testHandlerGracefulBlockedEarlyCommit() throws Exception
    {
        CountDownLatch dispatchedToHandlerLatch = new CountDownLatch(1);
        GracefulHandler gracefulHandler = new GracefulHandler();
        gracefulHandler.setHandler(new BlockingReadHandler()
        {
            @Override
            protected void onBeforeRead(Request request, Response response) throws Exception
            {
                try (Blocker.Callback block = Blocker.callback())
                {
                    LOG.debug("Response commit (early): {}", request.getHttpURI());
                    Content.Sink.write(response, false, "", block);
                    block.block();
                }
                assertTrue(response.isCommitted(), "Response expected to be committed");
                dispatchedToHandlerLatch.countDown();
            }
        });
        server = createServer(gracefulHandler);
        server.setStopTimeout(10000);
        server.start();

        // Body is incomplete (send 5 bytes out of 10)
        String rawRequest = """
            POST /?hint=incomplete_body HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            12345""";

        Socket client0 = newSocketToServer("client0");
        OutputStream output0 = client0.getOutputStream();
        InputStream input0 = client0.getInputStream();

        // Write incomplete request
        output0.write(rawRequest.getBytes(StandardCharsets.UTF_8));
        output0.flush();

        // Wait for request to reach handler
        assertTrue(dispatchedToHandlerLatch.await(2, TimeUnit.SECONDS), "Request didn't reach handler");

        // Trigger stop
        CompletableFuture<Long> stopFuture = runAsyncServerStop();

        // Wait till we enter graceful mode
        await().atMost(5, TimeUnit.SECONDS).until(() -> gracefulHandler.isShutdown());

        // Send rest of data
        output0.write("67890".getBytes(StandardCharsets.UTF_8));
        output0.flush();

        // Test response (should not report as closed, due to early commit)
        HttpTester.Response response = HttpTester.parseResponse(input0);
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(HttpHeader.CONNECTION), is(nullValue()));
        assertEquals("chunked", response.get(HttpHeader.TRANSFER_ENCODING), client0 + " transfer-encoding header");
        assertThat(response.getContent(), is("(Read:10) (Content-Length:10)"));

        // Verify Stop duration
        long stopDuration = stopFuture.get();
        assertThat(stopDuration, lessThan(5000L));

        // Ensure client connection is closed
        long beginClose = NanoTime.now();
        // The socket should have been closed
        assertThat(client0 + " should have been closed", input0.read(), is(-1));
        assertThat(client0 + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));
    }

    /**
     * Test of how the {@link GracefulHandler} should behave if it
     * receives a request on an active connection after graceful starts.
     */
    @Test
    public void testRequestAfterGraceful() throws Exception
    {
        GracefulHandler gracefulHandler = new GracefulHandler();
        gracefulHandler.setHandler(new BlockingReadHandler());
        server = createServer(gracefulHandler);
        server.setStopTimeout(10000);
        server.start();

        // Complete request
        String rawRequest = """
            POST /?num=%d HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            1234567890""";

        Socket client0 = newSocketToServer("client0");
        OutputStream output0 = client0.getOutputStream();
        InputStream input0 = client0.getInputStream();
        HttpTester.Response response;

        // Send one normal request to server
        output0.write(rawRequest.formatted(1).getBytes(StandardCharsets.UTF_8));
        output0.flush();

        // Verify response
        response = HttpTester.parseResponse(client0.getInputStream());
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(HttpHeader.CONNECTION), is(nullValue()));
        assertThat(response.getContent(), is("(Read:10) (Content-Length:10)"));

        // Trigger stop
        CompletableFuture<Long> stopFuture = runAsyncServerStop();

        // Wait till we enter graceful mode
        await().atMost(5, TimeUnit.SECONDS).until(() -> gracefulHandler.isShutdown());

        // Send another request on same connection
        output0.write(rawRequest.formatted(2).getBytes(StandardCharsets.UTF_8));
        output0.flush();

        // Verify response (should be a 503)
        response = HttpTester.parseResponse(client0.getInputStream());
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.SERVICE_UNAVAILABLE_503));
        assertThat(response.get(HttpHeader.CONNECTION), is("close"));

        // Verify Stop duration
        long stopDuration = stopFuture.get();
        assertThat(stopDuration, lessThan(5000L));

        // Ensure client connection is closed
        InputStream in = client0.getInputStream();
        long beginClose = NanoTime.now();
        // The socket should have been closed
        assertThat(client0 + " not closed", in.read(), is(-1));
        assertThat(client0 + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));

        // Restart the server to make sure handling resumes normally
        server.start();

        client0 = newSocketToServer("client0");
        output0 = client0.getOutputStream();

        // Send one normal request to server
        output0.write(rawRequest.formatted(1).getBytes(StandardCharsets.UTF_8));
        output0.flush();

        // Verify response
        response = HttpTester.parseResponse(client0.getInputStream());
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(HttpHeader.CONNECTION), is(nullValue()));
        assertThat(response.getContent(), is("(Read:10) (Content-Length:10)"));

        client0.close();
    }

    @Test
    public void testGracefulAlsoWaitsForStreamWrappers() throws Exception
    {
        GracefulHandler gracefulHandler = new GracefulHandler();
        LatchedStreamWrappingHandler handler = new LatchedStreamWrappingHandler();
        gracefulHandler.setHandler(handler);
        server = createServer(gracefulHandler);
        server.setStopTimeout(10000);
        server.start();

        // Complete request
        String rawRequest = """
            GET /?num=%d HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            \r
            """;

        Socket client0 = newSocketToServer("client0");
        OutputStream output0 = client0.getOutputStream();
        HttpTester.Response response;

        // Send one normal request to server
        output0.write(rawRequest.formatted(1).getBytes(StandardCharsets.UTF_8));
        output0.flush();

        // Verify response
        response = HttpTester.parseResponse(client0.getInputStream());
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(HttpHeader.CONNECTION), is(nullValue()));

        // Trigger stop
        CompletableFuture<Long> stopFuture = runAsyncServerStop();

        // Verify shutdown does not happen while a stream wrapper is pending
        await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> gracefulHandler.getCurrentStreamWrapperCount() > 0);

        // Unblock shutdown
        handler.latch.countDown();

        // Verify shutdown is done
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            long currentRequestCount = gracefulHandler.getCurrentRequestCount();
            long currentStreamWrapperCount = gracefulHandler.getCurrentStreamWrapperCount();
            return currentRequestCount == 0 && currentStreamWrapperCount == 0;
        });

        // Verify Stop duration
        long stopDuration = stopFuture.get();
        assertThat(stopDuration, lessThan(5000L));
    }

    static class LatchedStreamWrappingHandler extends Handler.Abstract
    {
        final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            LOG.debug("process: request={}", request);
            request.addHttpStreamWrapper(s -> new HttpStream.Wrapper(s)
            {
                @Override
                public void succeeded()
                {
                    try
                    {
                        if (!latch.await(5, TimeUnit.SECONDS))
                            throw new TimeoutException();
                        super.succeeded();
                    }
                    catch (Throwable x)
                    {
                        super.failed(x);
                    }
                }
            });
            Integer num = Request.getParameters(request).get("num").getValueAsInt();
            Content.Sink.write(response, true, "the body #" + num, callback);
            return true;
        }
    }

    /**
     * Simply reads the entire request body content, and replies with
     * how many bytes read, and what the request Content-Length said
     */
    static class BlockingReadHandler extends Handler.Abstract
    {
        private static final Logger LOG = LoggerFactory.getLogger(BlockingReadHandler.class);

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            LOG.debug("process: request={}", request);
            onBeforeRead(request, response);
            // Read request content (completely)
            int bytesRead = 0;
            long contentLength = request.getLength();
            Blocker.Shared blocking = new Blocker.Shared();
            if (contentLength > 0)
            {
                while (true)
                {
                    Content.Chunk chunk = request.read();
                    if (chunk == null)
                    {
                        try (Blocker.Runnable block = blocking.runnable())
                        {
                            request.demand(block);
                            block.block();
                            continue;
                        }
                    }
                    LOG.debug("chunk = {}", chunk);
                    if (Content.Chunk.isFailure(chunk))
                    {
                        Response.writeError(request, response, callback, chunk.getFailure());
                        return true;
                    }
                    bytesRead += chunk.remaining();
                    chunk.release();
                    if (chunk.isLast())
                        break;
                }
            }

            String responseBody = "(Read:%d) (Content-Length:%d)".formatted(bytesRead, contentLength);
            LOG.debug("Content.Sink.Write: {}", responseBody);
            Content.Sink.write(response, true, responseBody, callback);
            return true;
        }

        /**
         * Event indicating that this exchange is about to read from the request body
         *
         * @param request the request
         * @param response the response
         * @throws Exception if unable to perform action
         */
        protected void onBeforeRead(Request request, Response response) throws Exception
        {
            // override to trigger extra behavior in test case
        }
    }
}
