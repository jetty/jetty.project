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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.GracefulShutdownHandler;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GracefulShutdownTest
{
    private static final Logger LOG = LoggerFactory.getLogger(GracefulShutdownTest.class);
    private Server server;
    private ServerConnector connector;

    public Server createServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        connector.setIdleTimeout(10000);
        connector.setShutdownIdleTimeout(1000);
        connector.setPort(0);
        server.addConnector(connector);

        server.setHandler(handler);
        server.start();
        return server;
    }

    private Socket newSocketToServer(String id, String description) throws IOException
    {
        URI serverURI = server.getURI();
        Socket socket = new Socket(serverURI.getHost(), serverURI.getPort());
        LOG.info("{} : l={},r={} ({})", id, socket.getLocalSocketAddress(), socket.getRemoteSocketAddress(), description);
        return socket;
    }

    @Test
    public void testNoGracefulShutdownHandler() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        ContextHandler contextHandler = new ContextHandler("/");
        LatchHandler latchHandler = new LatchHandler();
        contextHandler.addHandler(latchHandler);
        contexts.addHandler(latchHandler);

        server = createServer(contexts);
        server.setStopTimeout(0);
        server.start();

        Socket client0 = newSocketToServer("client0", "incomplete POST request");
        Socket client1 = newSocketToServer("client1", "incomplete POST request");

        // A POST request that is incomplete.
        // Send only 5 bytes of a promised 10.
        String rawRequest = """
            POST /?hint=incomplete_body HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            12345""";

        List<Socket> clients = List.of(client0, client1);

        writeRequest(client0, rawRequest, latchHandler);
        writeRequest(client1, rawRequest, latchHandler);

        try (StacklessLogging ignore = new StacklessLogging(Response.class))
        {
            long start = NanoTime.now();
            server.stop(); // stop should have been quick
            assertThat(NanoTime.millisSince(start), lessThan(2000L));
        }

        for (Socket socket : clients)
        {
            InputStream in = socket.getInputStream();
            long beginClose = NanoTime.now();
            // The socket should have been closed
            assertThat(socket + " not closed", in.read(), is(-1));
            assertThat(socket + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));
        }

        // We should no longer see the LatchHandler in handling state
        assertFalse(latchHandler.handling.get());
        // There should have been a caught Throwable from the process() method
        // Likely something like a ClosedChannelException / IdleTimeoutException
        assertNotNull(latchHandler.thrown.get());
    }

    @Test
    public void testGracefulShutdownHandler() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        ContextHandler contextHandler = new ContextHandler("/");
        LatchHandler latchHandler = new LatchHandler();
        contextHandler.addHandler(latchHandler);
        contexts.addHandler(latchHandler);

        GracefulShutdownHandler gracefulShutdownHandler = new GracefulShutdownHandler();
        gracefulShutdownHandler.addHandler(contexts);
        server = createServer(gracefulShutdownHandler);
        server.setStopTimeout(10000);
        server.start();

        String rawIncompleteRequest = """
            POST /?hint=incomplete_request HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            12345""";

        String rawIncompleteRequestEarlyCommit = """
            POST /?commitEarly=true HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            12345""";

        String rawRequestComplete = """
            POST /?hint=complete_body HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            1234567890""";

        Socket client0 = newSocketToServer("client0", "incomplete request body");
        Socket client1 = newSocketToServer("client1", "incomplete request body, early response commit");
        Socket client2 = newSocketToServer("client2", "complete request, idle connection");

        // Incomplete request
        writeRequest(client0, rawIncompleteRequest, latchHandler);
        // Incomplete request, early commit
        writeRequest(client1, rawIncompleteRequestEarlyCommit, latchHandler);
        // Complete request
        writeRequest(client2, rawRequestComplete, latchHandler);

        // Response from complete request
        HttpTester.Response client2Response = HttpTester.parseResponse(client2.getInputStream());
        assertNotNull(client2Response);
        assertThat(client2Response.getStatus(), is(200));
        assertThat(client2Response.getContent(), is("read [10/10]"));
        assertThat(client2Response.get(HttpHeader.CONNECTION), nullValue());

        // Send remaining content to complete the request body contents
        writeBodyInThread(client0, "67890", latchHandler);
        writeBodyInThread(client1, "67890", latchHandler);

        // Graceful shutdown + stop
        try (StacklessLogging ignore = new StacklessLogging(Response.class))
        {
            // Trigger graceful stop
            long beginStop = NanoTime.now();
            server.stop();
            long duration = NanoTime.millisSince(beginStop);
            assertThat(duration, greaterThan(50L));
            assertThat(duration, lessThan(5000L));
        }

        HttpTester.Response response;

        // response that wasn't committed should have "Connection: close"
        response = HttpTester.parseResponse(client0.getInputStream());
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(HttpHeader.CONNECTION), is("close"));

        // response that was committed early cannot have a "Connection: close"
        response = HttpTester.parseResponse(client1.getInputStream());
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertNull(response.get(HttpHeader.CONNECTION));

        for (Socket socket : List.of(client0, client1, client2))
        {
            InputStream in = socket.getInputStream();
            long beginClose = NanoTime.now();
            // The socket should have been closed
            assertThat(socket + " not closed", in.read(), is(-1));
            assertThat(socket + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));
        }

        // We should no longer see the LatchHandler in handling state
        assertFalse(latchHandler.handling.get());
        // There should be no caught Throwable from the process() method
        assertThat(latchHandler.thrown.get(), nullValue());
    }

    @Test
    public void testGracefulShutdownHandlerWithIncompleteRequest() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        ContextHandler contextHandler = new ContextHandler("/");
        LatchHandler latchHandler = new LatchHandler();
        contextHandler.addHandler(latchHandler);
        contexts.addHandler(latchHandler);

        GracefulShutdownHandler gracefulShutdownHandler = new GracefulShutdownHandler();
        gracefulShutdownHandler.addHandler(contexts);
        server = createServer(gracefulShutdownHandler);
        server.setStopTimeout(10000);
        server.start();

        String rawIncompleteRequest = """
            POST /?hint=incomplete_body HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            12345""";

        String rawIncompleteRequestEarlyCommit = """
            POST /?commitEarly=true HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            12345""";

        String rawRequestComplete = """
            POST /?hint=complete_body HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            1234567890""";

        Socket client0 = newSocketToServer("client0", "incomplete request body");
        Socket client1 = newSocketToServer("client1", "incomplete request body, early response commit");
        Socket client2 = newSocketToServer("client2", "complete request, idle connection");

        writeRequest(client0, rawIncompleteRequest, latchHandler);
        writeRequest(client1, rawIncompleteRequestEarlyCommit, latchHandler);
        writeRequest(client2, rawRequestComplete, latchHandler);

        // Graceful shutdown + stop
        try (StacklessLogging ignore = new StacklessLogging(Response.class))
        {
            // Trigger graceful stop
            long beginStop = NanoTime.now();
            server.stop();
            long duration = NanoTime.millisSince(beginStop);
            assertThat(duration, greaterThan(50L));
            assertThat(duration, lessThan(5000L));
        }

        HttpTester.Response response;

        // Client waiting for response body data, but hasn't committed, should return a status/error 500.
        response = HttpTester.parseResponse(client0.getInputStream());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus(), client0 + " response status");
        assertEquals("close", response.get(HttpHeader.CONNECTION), client0 + " connection header");

        // Client waiting for response body data, but committed early, should return a status 200
        // that was sent with the early commit, with a Transfer-Encoding: chunked,
        // but the body content should not have arrived.
        String rawResponse = IO.toString(client1.getInputStream()); // capture till EOF
        response = HttpTester.parseResponse(rawResponse);
        assertEquals(HttpStatus.OK_200, response.getStatus(), client1 + " response status");
        assertEquals("chunked", response.get(HttpHeader.TRANSFER_ENCODING), client1 + " transfer-encoding header");
        assertNull(response.get(HttpHeader.CONNECTION), client1 + " connection header");

        // Client that sent a complete request body, should have seen a response with a status 200.
        response = HttpTester.parseResponse(client2.getInputStream());
        assertEquals(HttpStatus.OK_200, response.getStatus(), client2 + " response status");
        assertNull(response.get(HttpHeader.CONNECTION), client2 + " connection header");
        assertEquals("read [10/10]", response.getContent(), client2 + " response body");

        // All 3 clients should have their connections closed quickly
        for (Socket socket : List.of(client0, client1, client2))
        {
            InputStream in = socket.getInputStream();
            long beginClose = NanoTime.now();
            // The socket should have been closed
            assertThat(socket + " not closed", in.read(), is(-1));
            assertThat(socket + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));
        }

        // We should no longer see the LatchHandler in handling state
        assertFalse(latchHandler.handling.get());
        // There should have been a caught Throwable from the process() method
        // Likely something like a ClosedChannelException / IdleTimeoutException
        assertNotNull(latchHandler.thrown.get());
    }

    @Test
    public void testContextWithGracefulShutdownHandler() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        ContextHandler contextHandler = new ContextHandler("/a");
        LatchHandler latchHandler = new LatchHandler();
        contextHandler.addHandler(latchHandler);
        contexts.addHandler(latchHandler);

        GracefulShutdownHandler gracefulShutdownHandler = new GracefulShutdownHandler();
        gracefulShutdownHandler.addHandler(contexts);
        server = createServer(gracefulShutdownHandler);
        server.setStopTimeout(10000);
        server.start();

        String rawIncompleteRequest = """
            POST /a/?hint=incomplete_body HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            12345""";

        String rawIncompleteRequestEarlyCommit = """
            POST /a/?commitEarly=true HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            12345""";

        String rawRequestComplete = """
            POST /a/?hint=complete_body HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            1234567890""";

        Socket client0 = newSocketToServer("client0", "incomplete request body");
        Socket client1 = newSocketToServer("client1", "incomplete request body, early response commit");
        Socket client2 = newSocketToServer("client2", "complete request, idle connection");

        writeRequest(client0, rawIncompleteRequest, latchHandler);
        writeRequest(client1, rawIncompleteRequestEarlyCommit, latchHandler);
        writeRequest(client2, rawRequestComplete, latchHandler);

        HttpTester.Response response;
        // Verify client2 response (to first request)
        response = HttpTester.parseResponse(client2.getInputStream());
        assertEquals(HttpStatus.OK_200, response.getStatus(), client2 + " response status");
        assertNull(response.get(HttpHeader.CONNECTION), client2 + " connection header");
        assertEquals("read [10/10]", response.getContent(), client2 + " response body");

        // Send remaining content to complete the request body contents
        writeBodyInThread(client0, "67890", latchHandler);
        writeBodyInThread(client1, "67890", latchHandler);

        // Setup thread to attempt to send a second (complete) request on client2, once the context is unavailable
        CompletableFuture<Void> postAvailableRequestFuture =
            CompletableFuture.runAsync(() ->
            {
                await().until(() -> !contextHandler.isAvailable());
            }).thenRun(() ->
            {
                String rawRequestComplete2 = """
                    POST /a/?hint=complete_body?num=2 HTTP/1.1\r
                    Host: localhost\r
                    Content-Type: text/plain\r
                    Content-Length: 10\r
                    \r
                    1234567890""";
                try
                {
                    writeRequest(client2, rawRequestComplete2);
                }
                catch (Throwable t)
                {
                    throw new RuntimeException(t);
                }
            });

        // Graceful shutdown + stop
        try (StacklessLogging ignore = new StacklessLogging(Response.class))
        {
            // Trigger graceful stop
            long beginStop = NanoTime.now();
            server.stop();
            long duration = NanoTime.millisSince(beginStop);
            assertThat(duration, greaterThan(50L));
            assertThat(duration, lessThan(5000L));
        }

        // allow second request to complete being sent
        postAvailableRequestFuture.get();

        // Client receiving late response body complete, but hasn't committed early, should see a response status 200.
        response = HttpTester.parseResponse(client0.getInputStream());
        assertEquals(HttpStatus.OK_200, response.getStatus(), client0 + " response status");
        assertEquals("close", response.get(HttpHeader.CONNECTION), client0 + " connection header");
        assertEquals("read [10/10]", response.getContent(), client0 + " response body");

        // Client receiving late response body data, but committed early, should see a response status 200
        // that was sent with the early commit, with a Transfer-Encoding: chunked,
        // but the body content should have arrived.
        response = HttpTester.parseResponse(client1.getInputStream());
        assertEquals(HttpStatus.OK_200, response.getStatus(), client1 + " response status");
        assertEquals("chunked", response.get(HttpHeader.TRANSFER_ENCODING), client1 + " transfer-encoding header");
        assertNull(response.get(HttpHeader.CONNECTION), client1 + " connection header");
        assertEquals("read [10/10]", response.getContent(), client1 + " response body");

        // Client that sent the second complete request body, should see a response status 503.
        response = HttpTester.parseResponse(client2.getInputStream());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE_503, response.getStatus(), client2 + " response status");
        assertEquals("close", response.get(HttpHeader.CONNECTION), client2 + " connection header");

        // All 3 clients should have their connections closed quickly
        for (Socket socket : List.of(client0, client1, client2))
        {
            InputStream in = socket.getInputStream();
            long beginClose = NanoTime.now();
            // The socket should have been closed
            assertThat(socket + " not closed", in.read(), is(-1));
            assertThat(socket + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));
        }

        // We should no longer see the LatchHandler in handling state
        assertFalse(latchHandler.handling.get(), "LatchHandler should not be in an active process");
        // There should have been a caught Throwable from the process() method
        // Likely something like a ClosedChannelException / IdleTimeoutException
        assertEquals(0, latchHandler.errors.size(), "LatchHandler seen errors count");
    }

    @Test
    public void testContextWithoutGracefulShutdownHandler() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        ContextHandler contextHandler = new ContextHandler("/b");
        LatchHandler latchHandler = new LatchHandler();
        contextHandler.addHandler(latchHandler);
        contexts.addHandler(latchHandler);

        server = createServer(contexts);
        server.setStopTimeout(10000);
        server.start();

        String rawIncompleteRequest = """
            POST /b/?hint=incomplete_body HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            12345""";

        String rawIncompleteRequestEarlyCommit = """
            POST /b/?commitEarly=true HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            12345""";

        String rawRequestComplete = """
            POST /b/?hint=complete_body HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            1234567890""";

        Socket client0 = newSocketToServer("client0", "incomplete request body");
        Socket client1 = newSocketToServer("client1", "incomplete request body, early response commit");
        Socket client2 = newSocketToServer("client2", "complete request, idle connection");

        writeRequest(client0, rawIncompleteRequest, latchHandler);
        writeRequest(client1, rawIncompleteRequestEarlyCommit, latchHandler);
        writeRequest(client2, rawRequestComplete, latchHandler);

        HttpTester.Response response;
        // Verify client2 response (to first request)
        response = HttpTester.parseResponse(client2.getInputStream());
        assertEquals(HttpStatus.OK_200, response.getStatus(), client2 + " response status");
        assertNull(response.get(HttpHeader.CONNECTION), client2 + " connection header");
        assertEquals("read [10/10]", response.getContent(), client2 + " response body");

        // Send remaining content to complete the request body contents
        writeBodyInThread(client0, "67890", latchHandler);
        writeBodyInThread(client1, "67890", latchHandler);

        // Setup thread to attempt to send a second (complete) request on client2, once the context is unavailable
        CompletableFuture<Void> postAvailableRequestFuture =
            CompletableFuture.runAsync(() ->
            {
                await().until(() -> !contextHandler.isAvailable());
            }).thenRun(() ->
            {
                String rawRequestComplete2 = """
                    POST /b/?hint=complete_body?num=2 HTTP/1.1\r
                    Host: localhost\r
                    Content-Type: text/plain\r
                    Content-Length: 10\r
                    \r
                    1234567890""";
                try
                {
                    writeRequest(client2, rawRequestComplete2);
                }
                catch (Throwable t)
                {
                    throw new RuntimeException(t);
                }
            });

        // Graceful shutdown + stop
        try (StacklessLogging ignore = new StacklessLogging(Response.class))
        {
            // Trigger graceful stop
            long beginStop = NanoTime.now();
            server.stop();
            long duration = NanoTime.millisSince(beginStop);
            assertThat(duration, greaterThan(50L));
            assertThat(duration, lessThan(5000L));
        }

        // allow second request to complete being sent
        postAvailableRequestFuture.get();

        // Client receiving late response body complete, but hasn't committed early, should see a response status 200.
        response = HttpTester.parseResponse(client0.getInputStream());
        assertEquals(HttpStatus.OK_200, response.getStatus(), client0 + " response status");
        assertEquals("close", response.get(HttpHeader.CONNECTION), client0 + " connection header");
        assertEquals("read [10/10]", response.getContent(), client0 + " response body");

        // Client receiving late response body data, but committed early, should see a response status 200
        // that was sent with the early commit, with a Transfer-Encoding: chunked,
        // but the body content should have arrived.
        response = HttpTester.parseResponse(client1.getInputStream());
        assertEquals(HttpStatus.OK_200, response.getStatus(), client1 + " response status");
        assertEquals("chunked", response.get(HttpHeader.TRANSFER_ENCODING), client1 + " transfer-encoding header");
        assertNull(response.get(HttpHeader.CONNECTION), client1 + " connection header");
        assertEquals("read [10/10]", response.getContent(), client1 + " response body");

        // Client that sent the second complete request body, should see a response status 200.
        // The ServerConnector is graceful, and waiting for the EndPoint to be closed.
        // The requests can still enter the handler, and if fast enough can be handled.
        // The Connection header, however, should indicate that the connection is being closed.
        response = HttpTester.parseResponse(client2.getInputStream());
        assertEquals(HttpStatus.OK_200, response.getStatus(), client2 + " response status");
        assertEquals("close", response.get(HttpHeader.CONNECTION), client2 + " connection header");

        // All 3 clients should have their connections closed quickly
        for (Socket socket : List.of(client0, client1, client2))
        {
            InputStream in = socket.getInputStream();
            long beginClose = NanoTime.now();
            // The socket should have been closed
            assertThat(socket + " not closed", in.read(), is(-1));
            assertThat(socket + " close took too long", NanoTime.millisSince(beginClose), lessThan(2000L));
        }

        // We should no longer see the LatchHandler in handling state
        assertFalse(latchHandler.handling.get(), "LatchHandler should not be in an active process");
        // There should have been a caught Throwable from the process() method
        // Likely something like a ClosedChannelException / IdleTimeoutException
        assertEquals(0, latchHandler.errors.size(), "LatchHandler seen errors count");
    }

    private void writeRequest(Socket socket, String rawRequest, LatchHandler latchHandler) throws IOException, InterruptedException
    {
        latchHandler.latch = new CountDownLatch(1);
        writeRequest(socket, rawRequest);
        // wait till we confirm hitting the handler
        assertTrue(latchHandler.latch.await(5, TimeUnit.SECONDS), () ->
        {
            return "Didn't reach handler latch for " + socket;
        });
    }

    private void writeRequest(Socket socket, String rawRequest) throws IOException, InterruptedException
    {
        OutputStream out = socket.getOutputStream();
        out.write(rawRequest.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void writeBodyInThread(Socket socket, String content, LatchHandler latchHandler) throws ExecutionException, InterruptedException
    {
        long start = NanoTime.now();
        String threadName = "write-body-in-thread";
        Thread thread = new Thread(() ->
        {
            try
            {
                latchHandler.latch.await(10, TimeUnit.SECONDS);
                Thread.sleep(100 - NanoTime.millisSince(start));
                OutputStream out = socket.getOutputStream();
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            catch (Throwable t)
            {
                t.printStackTrace();
            }
        }, threadName);
        thread.start();
    }

    public record ErrorContext(HttpURI requestURI, Throwable thrown) {}

    static class LatchHandler extends Handler.Abstract
    {
        private static final Logger LOG = LoggerFactory.getLogger(LatchHandler.class);
        final ConcurrentLinkedQueue<ErrorContext> errors = new ConcurrentLinkedQueue<>();
        @Deprecated // replace with errors
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        @Deprecated // replace with count
        final AtomicBoolean handling = new AtomicBoolean(false);
        volatile CountDownLatch latch;

        static
        {
            LOG.info("This is my name: {}", LOG.getName());
        }

        @Override
        public boolean process(Request request, Response response, Callback callback) throws Exception
        {
            LOG.debug("Handling: {}", request.getHttpURI());
            handling.set(true);
            try
            {
                response.setStatus(200);
                Fields fields = Request.extractQueryParameters(request);
                if ("true".equals(fields.getValue("commitEarly")))
                {
                    try (Blocker.Callback block = Blocker.callback())
                    {
                        LOG.debug("Response commit (early): {}", request.getHttpURI());
                        Content.Sink.write(response, false, "", block);
                        block.block();
                    }
                    assertTrue(response.isCommitted(), "Response expected to be committed");
                }
                CountDownLatch l = latch;
                if (l != null)
                    l.countDown();

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
                        LOG.debug("chunk={}", chunk);
                        if (chunk instanceof Content.Chunk.Error error)
                            throw error.getCause();
                        bytesRead += chunk.remaining();
                        chunk.release();
                        if (chunk.isLast())
                            break;
                    }
                }

                String responseBody = "read [%d/%d]".formatted(bytesRead, contentLength);
                LOG.debug("Content.Sink.Write \"{}\": {}", responseBody, request.getHttpURI());
                Content.Sink.write(response, true, responseBody, callback);
            }
            catch (Throwable t)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Handling catch: {}", request.getHttpURI(), t);
                errors.add(new ErrorContext(request.getHttpURI(), t));
                thrown.set(t);
                callback.failed(t);
            }
            finally
            {
                handling.set(false);
                callback.succeeded();
            }
            return true;
        }
    }
}
