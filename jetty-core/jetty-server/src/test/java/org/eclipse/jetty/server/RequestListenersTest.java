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
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RequestListenersTest
{
    private Server server;
    private LocalConnector connector;
    private ContextHandler context;

    private void startServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
        context = new ContextHandler("/");
        server.setHandler(context);
        context.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testCompletionListeners() throws Exception
    {
        Queue<String> history = new ConcurrentLinkedQueue<>();
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Completion listeners are invoked in reverse order
                // because they are based on HttpStream wrapping.
                Request.addCompletionListener(request, t -> history.add("four"));
                Request.addCompletionListener(request, t -> history.add("three"));
                request.addHttpStreamWrapper(s -> new HttpStream.Wrapper(s)
                {
                    @Override
                    public void succeeded()
                    {
                        history.add("two");
                        super.succeeded();
                    }
                });
                Request.addCompletionListener(request, t -> history.add("one"));

                callback.succeeded();

                history.add("zero");
                return true;
            }
        });

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET / HTTP/1.1
            Host: localhost
            Connection: close
                            
            """));

        assertEquals(HttpStatus.OK_200, response.getStatus());
        await().atMost(5, TimeUnit.SECONDS).until(() -> history, contains("zero", "one", "two", "three", "four"));
    }

    @Test
    public void testListenersAreCalledInContext() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(3);
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                assertThat(ContextHandler.getCurrentContext(), sameInstance(context.getContext()));
                latch.countDown();

                request.addIdleTimeoutListener(t ->
                {
                    assertThat(ContextHandler.getCurrentContext(), sameInstance(context.getContext()));
                    latch.countDown();
                    return true;
                });

                request.addFailureListener(t ->
                {
                    assertThat(ContextHandler.getCurrentContext(), sameInstance(context.getContext()));
                    callback.failed(t);
                    latch.countDown();
                });

                return true;
            }
        });
        long idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET /path HTTP/1.0
            Host: localhost
                        
            """));

        assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    public void testIdleTimeoutListenerCompletesCallback(boolean failIdleTimeout, boolean succeedCallback) throws Exception
    {
        CountDownLatch failureLatch = new CountDownLatch(1);
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addIdleTimeoutListener(x ->
                {
                    if (succeedCallback)
                        callback.succeeded();
                    else
                        callback.failed(x);
                    return failIdleTimeout;
                });

                request.addFailureListener(x -> failureLatch.countDown());

                return true;
            }
        });
        long idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            POST / HTTP/1.1
            Host: localhost
            Content-Length: 1
            Connection: close
                            
            """, 2 * idleTimeout, TimeUnit.MILLISECONDS));

        int expectedStatus = succeedCallback ? HttpStatus.OK_200 : HttpStatus.INTERNAL_SERVER_ERROR_500;
        assertEquals(expectedStatus, response.getStatus());
        // The failure listener is never invoked because completing the callback
        // produces a response that completes the stream so the failure is ignored.
        assertThat(failureLatch.await(idleTimeout + 500, TimeUnit.MILLISECONDS), is(false));
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    public void testIdleTimeoutListenerFailsRequest(boolean failIdleTimeout, boolean succeedCallback) throws Exception
    {
        AtomicInteger failures = new AtomicInteger();
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addIdleTimeoutListener(x ->
                {
                    // Fail the request side, we should
                    // be able to write any response.
                    request.fail(x);
                    return failIdleTimeout;
                });

                request.addFailureListener(x ->
                {
                    failures.incrementAndGet();
                    if (succeedCallback)
                        callback.succeeded();
                    else
                        callback.failed(x);
                });
                return true;
            }
        });
        long idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            POST / HTTP/1.1
            Host: localhost
            Content-Length: 1
                            
            """, 2 * idleTimeout, TimeUnit.MILLISECONDS));

        int expectedStatus = succeedCallback ? HttpStatus.OK_200 : HttpStatus.INTERNAL_SERVER_ERROR_500;
        assertEquals(expectedStatus, response.getStatus());
        assertEquals(HttpHeaderValue.CLOSE.asString(), response.get(HttpHeader.CONNECTION));
        assertEquals(1, failures.get());
    }

    @Test
    public void testIdleTimeoutListenerInvokedMultipleTimesWhenReturningFalse() throws Exception
    {
        AtomicInteger idleTimeouts = new AtomicInteger();
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addIdleTimeoutListener(x ->
                {
                    if (idleTimeouts.incrementAndGet() == 2)
                        callback.succeeded();
                    return false;
                });

                return true;
            }
        });
        long idleTimeout = 500;
        connector.setIdleTimeout(idleTimeout);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET / HTTP/1.1
            Host: localhost
            Connection: close
                            
            """, 3 * idleTimeout, TimeUnit.MILLISECONDS));

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(idleTimeouts.get(), greaterThan(1));
    }

    @Test
    public void testIdleTimeoutListenerReturnsFalseThenAsyncSendResponse() throws Exception
    {
        AtomicReference<Response> responseRef = new AtomicReference<>();
        CompletableFuture<Callback> callbackOnTimeout = new CompletableFuture<>();
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                responseRef.set(response);
                request.addIdleTimeoutListener(t ->
                {
                    callbackOnTimeout.complete(callback);
                    return false; // ignore timeout
                });
                return true;
            }
        });
        long idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);

        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest("""
            POST / HTTP/1.1
            Host: localhost
            Content-Length: 1
            Connection: close
                            
            """))
        {

            // Get the callback as promised by the error listener.
            Callback callback = callbackOnTimeout.get(2 * idleTimeout, TimeUnit.MILLISECONDS);
            assertNotNull(callback);
            Content.Sink.write(responseRef.get(), true, "OK", callback);

            HttpTester.Response response = HttpTester.parseResponse(endPoint.waitForResponse(false, 3 * idleTimeout, TimeUnit.MILLISECONDS));

            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), is("OK"));
        }
    }

    @Test
    public void testIdleTimeoutListenerReturnsFalseThenTrue() throws Exception
    {
        AtomicReference<Throwable> idleTimeoutFailure = new AtomicReference<>();
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addIdleTimeoutListener(t -> idleTimeoutFailure.getAndSet(t) != null);
                request.addFailureListener(callback::failed);
                return true;
            }
        });
        long idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            POST / HTTP/1.1
            Host: localhost
            Content-Length: 1
            Connection: close
                            
            """, 3 * idleTimeout, TimeUnit.MILLISECONDS));

        // The first time the listener returns false, but does not
        // complete the callback, so another idle timeout elapses.
        // The second time the listener returns true, the failure
        // listener is called, which fails the Handler callback.
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContent(), containsStringIgnoringCase("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout"));
        assertThat(idleTimeoutFailure.get(), instanceOf(TimeoutException.class));
    }

    @Test
    public void testIdleTimeoutWithoutIdleTimeoutListener() throws Exception
    {
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Handler does not complete the callback, but the failure listener does.
                request.addFailureListener(callback::failed);
                return true;
            }
        });
        long idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET / HTTP/1.1
            Host: localhost
            Connection: close
                        
            """));
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContent(), containsString("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout expired:"));
    }

    @Test
    public void testIdleTimeoutInvokesDemandCallback() throws Exception
    {
        AtomicReference<Request> requestRef = new AtomicReference<>();
        CompletableFuture<Callback> callbackCompletable = new CompletableFuture<>();
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                requestRef.set(request);
                request.demand(() -> callbackCompletable.complete(callback));
                return true;
            }
        });
        long idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);

        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest("""
            POST / HTTP/1.1
            Host: localhost
            Content-Length: 1
            Connection: close
                        
            """))
        {

            Callback callback = callbackCompletable.get(5 * idleTimeout, TimeUnit.MILLISECONDS);
            Request request = requestRef.get();

            Content.Chunk chunk = request.read();
            assertNotNull(chunk);
            assertTrue(Content.Chunk.isFailure(chunk, false));
            chunk = request.read();
            assertNull(chunk);

            callback.succeeded();

            HttpTester.Response response = HttpTester.parseResponse(endPoint.waitForResponse(false, idleTimeout, TimeUnit.MILLISECONDS));

            assertThat(response.getStatus(), is(HttpStatus.OK_200));
        }
    }

    // TODO: test pending writes are failed fatally, but you can still read and still have to complete callback.

    @Test
    public void testIdleTimeoutFailsWriteCallback() throws Exception
    {
        AtomicReference<Response> responseRef = new AtomicReference<>();
        CompletableFuture<Callback> writeFailed = new CompletableFuture<>();
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                responseRef.set(response);

                // Issue a large write that will be congested.
                // The idle timeout should fail the write callback.
                ByteBuffer byteBuffer = ByteBuffer.allocate(128 * 1024 * 1024);
                response.write(false, byteBuffer, Callback.from(() -> {}, x -> writeFailed.complete(callback)));

                return true;
            }
        });
        long idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);

        try (LocalConnector.LocalEndPoint endPoint = connector.connect())
        {
            // Do not grow the output so the response will be congested.
            endPoint.setGrowOutput(false);
            endPoint.addInputAndExecute("""
                POST / HTTP/1.1
                Host: localhost
                Content-Length: 1
                Connection: close
                            
                """);

            Callback callback = writeFailed.get(5 * idleTimeout, TimeUnit.MILLISECONDS);

            // Cannot write anymore.
            Response response = responseRef.get();
            CountDownLatch writeFailedLatch = new CountDownLatch(1);
            // Use a non-empty buffer to avoid short-circuit the write.
            response.write(false, ByteBuffer.allocate(16), Callback.from(() -> {}, x -> writeFailedLatch.countDown()));
            assertTrue(writeFailedLatch.await(5, TimeUnit.SECONDS));

            // The write side has failed, but the read side has not.
            Request request = response.getRequest();
            Content.Chunk chunk = request.read();
            assertNull(chunk);

            // Since we cannot write, only choice is to fail the Handler callback.
            callback.failed(new IOException());

            // Should throw.
            endPoint.waitForResponse(false, idleTimeout, TimeUnit.MILLISECONDS);
        }
    }
}
