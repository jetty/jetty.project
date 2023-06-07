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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.server.internal.HttpConnection;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Invocable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTest
{
    private static final long IDLE_TIMEOUT = 1000L;
    private Server _server;
    private LocalConnector _connector;
    private final AtomicReference<Runnable> _afterHandle = new AtomicReference<>();

    @BeforeEach
    public void prepare() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server, new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                HttpConnection connection = new HttpConnection(getHttpConfiguration(), connector, endPoint, isRecordHttpComplianceViolations())
                {
                    @Override
                    protected HttpChannel newHttpChannel(Server server, HttpConfiguration configuration)
                    {
                        return new HttpChannelState(this)
                        {
                            @Override
                            public Runnable onRequest(MetaData.Request request)
                            {
                                Runnable onRequest = super.onRequest(request);
                                if (onRequest == null)
                                    return null;

                                return () ->
                                {
                                    try
                                    {
                                        onRequest.run();
                                    }
                                    finally
                                    {
                                        Runnable after = _afterHandle.getAndSet(null);
                                        if (after != null)
                                            getThreadPool().execute(after);
                                    }
                                };
                            }
                        };
                    }
                };
                connection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
                connection.setUseOutputDirectByteBuffers(isUseOutputDirectByteBuffers());
                return configure(connection, connector, endPoint);
            }
        });
        _connector.setIdleTimeout(IDLE_TIMEOUT);
        _server.addConnector(_connector);
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(_server);
        _connector = null;
    }

    @Test
    public void testSimpleGET() throws Exception
    {
        _server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                Content.Sink.write(response, true, "Hello", callback);
                return true;
            }
        });
        _server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), is("Hello"));
    }

    public static Stream<Arguments> completionScenarios()
    {
        List<Arguments> arguments = new ArrayList<>();
        for (Boolean succeeded : List.of(true, false))
        {
            for (Boolean handling : List.of(true, false))
            {
                for (Boolean written : List.of(true, false))
                {
                    for (Boolean last : written ? List.of(true, false) : List.of(false))
                    {
                        arguments.add(Arguments.of(succeeded, handling, written, last));
                    }
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("completionScenarios")
    public void testCompletion(boolean succeeded, boolean handling, boolean written, boolean last) throws Exception
    {
        _server.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                if (written)
                {
                    try (Blocker.Callback blocker = Blocker.callback())
                    {
                        Content.Sink.write(response, last, "Hello", blocker);
                        blocker.block();
                    }
                }

                Runnable complete = succeeded ? callback::succeeded : () -> callback.failed(new QuietException.Exception("Test"));
                if (handling)
                    complete.run();
                else
                    _afterHandle.set(complete);

                return true;
            }
        });
        _server.start();

        String request = """
                GET /path HTTP/1.1\r
                Host: hostname\r
                \r
                """;
        String rawResponse = _connector.getResponse(request);
        // System.err.printf("succeeded=%b handling=%b written=%b last=%b%n", succeeded, handling, written, last);
        // System.err.println(rawResponse);

        if (succeeded || written)
            assertThat(rawResponse, containsString("HTTP/1.1 200 OK"));
        else
            assertThat(rawResponse, containsString("HTTP/1.1 500 Server Error"));

        if (written)
            assertThat(rawResponse, containsString("Hello"));
        else
            assertThat(rawResponse, not(containsString("Hello")));

        if (written && !last)
        {
            assertThat(rawResponse, containsString("chunked"));
            if (succeeded)
                assertThat(rawResponse, containsString("\r\n0\r\n"));
            else
                assertThat(rawResponse, not(containsString("\r\n0\r\n")));
        }
        else
        {
            assertThat(rawResponse, containsString("Content-Length:"));
        }
    }

    @Test
    public void testIdleTimeoutNoListener() throws Exception
    {
        _server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Handler never completes the callback
                return true;
            }
        });
        _server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        String rawResponse = _connector.getResponse(request);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContent(), containsString("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout expired:"));
    }

    @Test
    public void testIdleTimeoutNoListenerDemand() throws Exception
    {
        CountDownLatch demanded = new CountDownLatch(1);
        AtomicReference<Request> requestRef = new AtomicReference<>();
        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        _server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Handler never completes the callback
                requestRef.set(request);
                callbackRef.set(callback);
                request.demand(demanded::countDown);
                return true;
            }
        });
        _server.start();

        String request = """
            GET /path HTTP/1.0\r
            Host: hostname\r
            Content-Length: 10\r
            \r
            """;
        try (LocalConnector.LocalEndPoint endPoint = _connector.executeRequest(request))
        {
            assertTrue(demanded.await(2 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS));
            Content.Chunk chunk = requestRef.get().read();
            assertThat(chunk, instanceOf(Content.Chunk.Error.class));
            Throwable cause = ((Content.Chunk.Error)chunk).getCause();
            assertThat(cause, instanceOf(TimeoutException.class));
            callbackRef.get().failed(cause);

            String rawResponse = endPoint.getResponse();
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
            assertThat(response.getContent(), containsString("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout expired:"));
        }
    }

    @Test
    public void testIdleTimeoutFalseListener() throws Exception
    {
        AtomicReference<Throwable> error = new AtomicReference<>();
        _server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addErrorListener(t ->
                {
                    error.set(t);
                    return false;
                });
                return true;
            }
        });
        _server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        String rawResponse = _connector.getResponse(request);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContent(), containsString("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout expired:"));
        assertThat(error.get(), instanceOf(TimeoutException.class));
    }

    @Test
    public void testIdleTimeoutTrueListener() throws Exception
    {
        CompletableFuture<Callback> callbackOnTimeout = new CompletableFuture<>();
        _server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addErrorListener(t ->
                {
                    if (t instanceof TimeoutException)
                    {
                        callbackOnTimeout.complete(callback);
                        return true;
                    }
                    return false;
                });
                return true;
            }
        });
        _server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;

        try (LocalConnector.LocalEndPoint localEndPoint = _connector.executeRequest(request))
        {
            callbackOnTimeout.get(3 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS).succeeded();
            String rawResponse = localEndPoint.getResponse();
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
        }
    }

    @Test
    public void testIdleTimeoutTrueFalseListener() throws Exception
    {
        AtomicReference<Throwable> error = new AtomicReference<>();
        _server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addErrorListener(t -> error.getAndSet(t) == null);
                return true;
            }
        });
        _server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        String rawResponse = _connector.getResponse(request);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContent(), containsString("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout expired:"));
        assertThat(error.get(), instanceOf(TimeoutException.class));
    }

    @Test
    public void testIdleTimeoutListenerTrueDemand() throws Exception
    {
        CountDownLatch demanded = new CountDownLatch(1);
        CountDownLatch recalled = new CountDownLatch(1);
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        AtomicReference<Request> requestRef = new AtomicReference<>();
        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        _server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Handler never completes the callback
                requestRef.set(request);
                callbackRef.set(callback);
                request.addErrorListener(t ->
                {
                    if (error.isDone())
                        recalled.countDown();
                    else
                        error.complete(t);
                    return true;
                });
                request.demand(demanded::countDown);

                return true;
            }
        });
        _server.start();

        String request = """
            GET /path HTTP/1.0\r
            Host: hostname\r
            Content-Length: 10\r
            \r
            """;
        try (LocalConnector.LocalEndPoint endPoint = _connector.executeRequest(request))
        {
            assertTrue(demanded.await(2 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS));
            Throwable t = error.get(IDLE_TIMEOUT / 2, TimeUnit.MILLISECONDS);
            assertThat(t, instanceOf(TimeoutException.class));
            Content.Chunk chunk = requestRef.get().read();
            assertThat(chunk, instanceOf(Content.Chunk.Error.class));
            Throwable cause = ((Content.Chunk.Error)chunk).getCause();
            assertThat(cause, instanceOf(TimeoutException.class));

            // wait for another timeout
            assertTrue(recalled.await(2 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS));

            callbackRef.get().succeeded();

            String rawResponse = endPoint.getResponse();
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
        }
    }
}
