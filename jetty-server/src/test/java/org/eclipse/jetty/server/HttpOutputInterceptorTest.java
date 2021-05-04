//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpOutputInterceptorTest
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpOutputInterceptorTest.class);

    private Server _server;
    private LocalConnector _localConnector;
    private HandlerCollection _handlerCollection;
    private StacklessLogging _stacklessLogging;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        HttpConfiguration config = new HttpConfiguration();
        config.setOutputBufferSize(1024);
        config.setOutputAggregationSize(256);

        _localConnector = new LocalConnector(_server, new HttpConnectionFactory(config));
        _localConnector.setIdleTimeout(Duration.ofMinutes(1).toMillis());
        _server.addConnector(_localConnector);
        ServerConnector serverConnector = new ServerConnector(_server, new HttpConnectionFactory(config));
        _server.addConnector(serverConnector);

        _handlerCollection = new HandlerCollection();
        _server.setHandler(_handlerCollection);
        _stacklessLogging = new StacklessLogging(HttpChannel.class);
    }

    @AfterEach
    public void after() throws Exception
    {
        _stacklessLogging.close();
        _server.stop();
    }

    @Test
    public void testInterceptorCallbackFailed() throws Exception
    {
        addInterceptor(nextInterceptor -> new HttpOutput.Interceptor()
        {
            @Override
            public void write(ByteBuffer content, boolean last, Callback callback)
            {
                callback.failed(new RuntimeException("callback failed from interceptor"));
            }

            @Override
            public HttpOutput.Interceptor getNextInterceptor()
            {
                return nextInterceptor;
            }
        });

        CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();
        _handlerCollection.addHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                byte[] content = "content to be written".getBytes(StandardCharsets.ISO_8859_1);
                HttpOutput httpOutput = baseRequest.getResponse().getHttpOutput();
                httpOutput.setBufferSize(content.length * 2);
                httpOutput.write(content);

                try
                {
                    // The will close will write the output buffered in HttpOutput to the interceptor which will fail the callback.
                    httpOutput.close();
                }
                catch (Throwable t)
                {
                    errorFuture.complete(t);
                    throw t;
                }
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(500));

        // We failed because of the next interceptor.
        Throwable error = errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(error.getMessage(), containsString("callback failed from interceptor"));
    }

    @Test
    public void testInterceptorThrows() throws Exception
    {
        addInterceptor(nextInterceptor -> new HttpOutput.Interceptor()
        {
            @Override
            public void write(ByteBuffer content, boolean last, Callback callback)
            {
                throw new RuntimeException("thrown from interceptor");
            }

            @Override
            public HttpOutput.Interceptor getNextInterceptor()
            {
                return nextInterceptor;
            }
        });

        CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();
        _handlerCollection.addHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                byte[] content = "content to be written".getBytes(StandardCharsets.ISO_8859_1);
                HttpOutput httpOutput = baseRequest.getResponse().getHttpOutput();
                httpOutput.setBufferSize(content.length * 2);
                httpOutput.write(content);

                try
                {
                    // The will close will write the output buffered in HttpOutput to the interceptor which will throw.
                    httpOutput.close();
                }
                catch (Throwable t)
                {
                    errorFuture.complete(t);
                    throw t;
                }
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(500));

        // We failed because of the next interceptor.
        Throwable error = errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(error.getMessage(), containsString("thrown from interceptor"));
    }

    @Test
    public void testAsyncWriteFailed() throws Exception
    {
        addInterceptor(nextInterceptor -> new HttpOutput.Interceptor()
        {
            @Override
            public void write(ByteBuffer content, boolean last, Callback callback)
            {
                callback.failed(new RuntimeException("callback failed from interceptor"));
            }

            @Override
            public HttpOutput.Interceptor getNextInterceptor()
            {
                return nextInterceptor;
            }
        });

        CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();
        AtomicInteger onErrorCount = new AtomicInteger(0);
        _handlerCollection.addHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                byte[] content = "content to be written".getBytes(StandardCharsets.ISO_8859_1);
                AsyncContext asyncContext = request.startAsync();
                ServletOutputStream outputStream = response.getOutputStream();
                baseRequest.getResponse().getHttpOutput().setBufferSize(0);
                outputStream.setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        while (outputStream.isReady())
                        {
                            outputStream.write(content);
                        }
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        // Record some information for testing.
                        onErrorCount.incrementAndGet();
                        errorFuture.complete(t);

                        // Try to send back a 500 response instead of aborting the connection.
                        if (!response.isCommitted())
                        {
                            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                            response.addHeader("throwableFromOnError", t.getMessage());
                        }

                        asyncContext.complete();
                    }
                });
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(500));

        assertThat(response.get("throwableFromOnError"), is("callback failed from interceptor"));

        assertThat(onErrorCount.get(), is(1));
        Throwable error = errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(error.getMessage(), containsString("callback failed from interceptor"));
    }

    @Test
    public void testAsyncFlushFailed() throws Exception
    {
        addInterceptor(nextInterceptor -> new HttpOutput.Interceptor()
        {
            @Override
            public void write(ByteBuffer content, boolean last, Callback callback)
            {
                callback.failed(new RuntimeException("callback failed from interceptor"));
            }

            @Override
            public HttpOutput.Interceptor getNextInterceptor()
            {
                return nextInterceptor;
            }
        });

        CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();
        AtomicInteger onErrorCount = new AtomicInteger(0);
        _handlerCollection.addHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                byte[] content = "content to be written".getBytes(StandardCharsets.ISO_8859_1);
                AsyncContext asyncContext = request.startAsync();
                baseRequest.getResponse().getHttpOutput().setBufferSize(content.length * 2);
                ServletOutputStream outputStream = response.getOutputStream();
                outputStream.setWriteListener(new WriteListener()
                {
                    private int state = 0;

                    @Override
                    public void onWritePossible() throws IOException
                    {
                        while (outputStream.isReady())
                        {
                            switch (state++)
                            {
                                case 0:
                                    outputStream.write(content);
                                    break;
                                case 1:
                                case 2:
                                    // First flush will cause be failed in the interceptor, second flush will throw the error.
                                    outputStream.flush();
                                    break;
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        // Record some information for testing.
                        onErrorCount.incrementAndGet();
                        errorFuture.complete(t);

                        // Try to send back a 500 response instead of aborting the connection.
                        if (!response.isCommitted())
                        {
                            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                            response.addHeader("throwableFromOnError", t.getMessage());
                        }

                        asyncContext.complete();
                    }
                });
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(500));
        assertThat(response.get("throwableFromOnError"), is("callback failed from interceptor"));

        assertThat(onErrorCount.get(), is(1));
        Throwable error = errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(error.getMessage(), containsString("callback failed from interceptor"));
    }

    @Test
    public void testAsyncCloseFailed() throws Exception
    {
        addInterceptor(nextInterceptor -> new HttpOutput.Interceptor()
        {
            @Override
            public void write(ByteBuffer content, boolean last, Callback callback)
            {
                callback.failed(new RuntimeException("callback failed from interceptor"));
            }

            @Override
            public HttpOutput.Interceptor getNextInterceptor()
            {
                return nextInterceptor;
            }
        });

        CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();
        AtomicInteger onErrorCount = new AtomicInteger(0);
        _handlerCollection.addHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                byte[] content = "content to be written".getBytes(StandardCharsets.ISO_8859_1);
                AsyncContext asyncContext = request.startAsync();
                baseRequest.getResponse().getHttpOutput().setBufferSize(content.length * 2);
                ServletOutputStream outputStream = response.getOutputStream();
                outputStream.setWriteListener(new WriteListener()
                {
                    private int state = 0;

                    @Override
                    public void onWritePossible() throws IOException
                    {
                        while (outputStream.isReady())
                        {
                            switch (state++)
                            {
                                case 0:
                                    outputStream.write(content);
                                    break;
                                case 1:
                                    outputStream.close();
                                    break;
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        // Record some information for testing.
                        onErrorCount.incrementAndGet();
                        errorFuture.complete(t);

                        // Try to send back a 500 response instead of aborting the connection.
                        if (!response.isCommitted())
                        {
                            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                            response.addHeader("throwableFromOnError", t.getMessage());
                        }

                        asyncContext.complete();
                    }
                });
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(500));
        assertThat(response.get("throwableFromOnError"), is("callback failed from interceptor"));

        assertThat(onErrorCount.get(), is(1));
        Throwable error = errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(error.getMessage(), containsString("callback failed from interceptor"));
    }

    @Test
    public void testAsyncFailureAfterClose() throws Exception
    {
        CountDownLatch failInterceptor = new CountDownLatch(1);
        addInterceptor(nextInterceptor -> new HttpOutput.Interceptor()
        {
            @Override
            public void write(ByteBuffer content, boolean last, Callback callback)
            {
                new Thread(() ->
                {
                    try
                    {
                        assertTrue(failInterceptor.await(10, TimeUnit.SECONDS));
                    }
                    catch (InterruptedException e)
                    {
                        throw new RuntimeException(e);
                    }
                    finally
                    {
                        callback.failed(new RuntimeException("callback failed from interceptor"));
                    }
                }).start();
            }

            @Override
            public HttpOutput.Interceptor getNextInterceptor()
            {
                return nextInterceptor;
            }
        });

        AtomicInteger onErrorCount = new AtomicInteger(0);
        _handlerCollection.addHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                byte[] content = "content to be written".getBytes(StandardCharsets.ISO_8859_1);
                AsyncContext asyncContext = request.startAsync();
                ServletOutputStream outputStream = response.getOutputStream();
                outputStream.setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        if (outputStream.isReady())
                        {
                            outputStream.write(content);
                            outputStream.close();
                            asyncContext.complete();
                            failInterceptor.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        onErrorCount.incrementAndGet();
                    }
                });
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        // Response is aborted (status code 0).
        // onError() was never called because the failure occurred after asyncContext.complete().
        assertThat(response.getStatus(), is(0));
        assertThat(onErrorCount.get(), is(0));
    }

    public static class InterceptorHolder extends AbstractHandler
    {
        Function<HttpOutput.Interceptor, HttpOutput.Interceptor> interceptorCreator;

        public InterceptorHolder(Function<HttpOutput.Interceptor, HttpOutput.Interceptor> interceptorCreator)
        {
            this.interceptorCreator = interceptorCreator;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            HttpOutput httpOutput = baseRequest.getResponse().getHttpOutput();
            httpOutput.setInterceptor(interceptorCreator.apply(httpOutput.getInterceptor()));
        }
    }

    public void addInterceptor(Function<HttpOutput.Interceptor, HttpOutput.Interceptor> interceptorCreator)
    {
        _handlerCollection.addHandler(new InterceptorHolder(interceptorCreator));
    }
}
