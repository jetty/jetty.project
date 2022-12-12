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

package org.eclipse.jetty.ee10.test.client.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.InputStreamRequestContent;
import org.eclipse.jetty.client.util.OutputStreamRequestContent;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.ee10.servlet.HttpOutput;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.transport.internal.HttpConnectionOverHTTP2;
import org.eclipse.jetty.http2.internal.HTTP2Session;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Disabled
public class AsyncIOServletTest extends AbstractTest
{
    private static final ThreadLocal<RuntimeException> scope = new ThreadLocal<>();

    @Override
    protected void prepareServer(Transport transport, HttpServlet servlet) throws Exception
    {
        super.prepareServer(transport, servlet);
        // Add this listener before the context is started, so it's durable.
        servletContextHandler.addEventListener(new ContextHandler.ContextScopeListener()
        {
            @Override
            public void enterScope(org.eclipse.jetty.server.Context context, Request request)
            {
                checkScope();
                scope.set(new RuntimeException());
            }

            @Override
            public void exitScope(org.eclipse.jetty.server.Context context, Request request)
            {
                assertScope();
                scope.set(null);
            }
        });
    }

    @Override
    public void dispose()
    {
        checkScope();
        super.dispose();
    }

    private static void assertScope()
    {
        assertNotNull(scope.get(), "Not in scope");
    }

    private static void checkScope()
    {
        RuntimeException callScope = scope.get();
        if (callScope != null)
            throw callScope;
    }

    private void sleep(long ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            throw new UncheckedIOException(new InterruptedIOException());
        }
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testAsyncReadThrowsException(Transport transport) throws Exception
    {
        testAsyncReadThrows(transport, new NullPointerException("explicitly_thrown_by_test"));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testAsyncReadThrowsError(Transport transport) throws Exception
    {
        testAsyncReadThrows(transport, new Error("explicitly_thrown_by_test"));
    }

    private void testAsyncReadThrows(Transport transport, Throwable throwable) throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                assertScope();
                AsyncContext asyncContext = request.startAsync(request, response);
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        assertScope();
                        if (throwable instanceof RuntimeException)
                            throw (RuntimeException)throwable;
                        if (throwable instanceof Error)
                            throw (Error)throwable;
                        throw new IOException(throwable);
                    }

                    @Override
                    public void onAllDataRead()
                    {
                        assertScope();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        assertThat("onError type", t, instanceOf(throwable.getClass()));
                        assertThat("onError message", t.getMessage(), is(throwable.getMessage()));
                        latch.countDown();
                        response.setStatus(500);
                        asyncContext.complete();
                    }
                });
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(new StringRequestContent("0123456789"))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testAsyncReadIdleTimeout(Transport transport) throws Exception
    {
        int status = 567;
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                assertScope();
                AsyncContext asyncContext = request.startAsync(request, response);
                asyncContext.setTimeout(0);
                ServletInputStream inputStream = request.getInputStream();
                inputStream.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        assertScope();
                        while (inputStream.isReady() && !inputStream.isFinished())
                        {
                            inputStream.read();
                        }
                    }

                    @Override
                    public void onAllDataRead()
                    {
                        assertScope();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        response.setStatus(status);
                        // Do not put Connection: close header here, the test
                        // verifies that the server closes no matter what.
                        asyncContext.complete();
                    }
                });
            }
        });
        setStreamIdleTimeout(1000);
        CountDownLatch closeLatch = new CountDownLatch(1);
        connector.addBean(new Connection.Listener()
        {
            @Override
            public void onOpened(Connection connection)
            {
            }

            @Override
            public void onClosed(Connection connection)
            {
                closeLatch.countDown();
            }
        });

        String data = "0123456789";
        AsyncRequestContent content = new AsyncRequestContent();
        content.write(ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)), Callback.NOOP);
        CountDownLatch responseLatch = new CountDownLatch(1);
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(content)
            .onResponseSuccess(r -> responseLatch.countDown())
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                assertEquals(status, result.getResponse().getStatus());
                clientLatch.countDown();
            });

        // HTTP/2 does not close a Connection when the request idle times out.
        if (transport != Transport.H2C && transport != Transport.H2)
            assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "close latch expired");
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS), "response latch expired");
        content.close();
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS), "client latch expired");
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testOnErrorThrows(Transport transport) throws Exception
    {
        AtomicInteger errors = new AtomicInteger();
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                assertScope();
                if (request.getDispatcherType() == DispatcherType.ERROR)
                {
                    response.flushBuffer();
                    return;
                }

                request.startAsync(request, response);
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {
                        assertScope();
                        throw new NullPointerException("explicitly_thrown_by_test_1");
                    }

                    @Override
                    public void onAllDataRead()
                    {
                        assertScope();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        errors.incrementAndGet();
                        throw new NullPointerException("explicitly_thrown_by_test_2")
                        {
                            {
                                this.initCause(t);
                            }
                        };
                    }
                });
            }
        });

        try (StacklessLogging ignore = new StacklessLogging(HttpChannelState.class))
        {
            ContentResponse response = client.newRequest(newURI(transport))
                .body(new StringRequestContent("0123456789"))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
            assertEquals(1, errors.get());
        }
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testAsyncWriteThrowsException(Transport transport) throws Exception
    {
        testAsyncWriteThrows(transport, new NullPointerException("explicitly_thrown_by_test"));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testAsyncWriteThrowsError(Transport transport) throws Exception
    {
        testAsyncWriteThrows(transport, new Error("explicitly_thrown_by_test"));
    }

    private void testAsyncWriteThrows(Transport transport, Throwable throwable) throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                assertScope();
                AsyncContext asyncContext = request.startAsync(request, response);
                response.getOutputStream().setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        assertScope();
                        if (throwable instanceof RuntimeException)
                            throw (RuntimeException)throwable;
                        if (throwable instanceof Error)
                            throw (Error)throwable;
                        throw new IOException(throwable);
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        latch.countDown();
                        response.setStatus(500);
                        asyncContext.complete();
                        assertSame(throwable, t);
                    }
                });
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testAsyncWriteClosed(Transport transport) throws Exception
    {
        byte[] data = new byte[1024];

        CountDownLatch errorLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                assertScope();
                response.flushBuffer();

                AsyncContext async = request.startAsync();
                ServletOutputStream out = response.getOutputStream();
                out.setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible()
                    {
                        assertScope();

                        // Wait for the failure to arrive to
                        // the server while we are about to write.
                        try
                        {
                            await().atMost(5, TimeUnit.SECONDS).until(() ->
                            {
                                try
                                {
                                    if (out.isReady())
                                        ((HttpOutput)out).write(ByteBuffer.wrap(data));
                                    return false;
                                }
                                catch (EofException e)
                                {
                                    return true;
                                }
                            });
                        }
                        catch (Exception e)
                        {
                            throw new AssertionError(e);
                        }
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        async.complete();
                        errorLatch.countDown();
                    }
                });
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .onResponseHeaders(response ->
            {
                if (response.getStatus() == HttpStatus.OK_200)
                    response.abort(new IOException("explicitly_closed_by_test"));
            })
            .send(result ->
            {
                if (result.isFailed())
                    clientLatch.countDown();
            });

        assertTrue(errorLatch.await(10, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(10, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testAsyncWriteLessThanContentLengthFlushed(Transport transport) throws Exception
    {
        CountDownLatch complete = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setContentLength(10);

                AsyncContext async = request.startAsync();
                ServletOutputStream out = response.getOutputStream();
                AtomicInteger state = new AtomicInteger(0);

                out.setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        while (true)
                        {
                            if (!out.isReady())
                                return;

                            switch (state.get())
                            {
                                case 0:
                                    state.incrementAndGet();
                                    WriteListener listener = this;
                                    new Thread(() ->
                                    {
                                        try
                                        {
                                            Thread.sleep(50);
                                            listener.onWritePossible();
                                        }
                                        catch (Exception ignored)
                                        {
                                            // no op
                                        }
                                    }).start();
                                    return;

                                case 1:
                                    state.incrementAndGet();
                                    out.flush();
                                    break;

                                case 2:
                                    state.incrementAndGet();
                                    out.write("12345".getBytes());
                                    break;

                                case 3:
                                    async.complete();
                                    complete.countDown();
                                    return;
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                    }
                });
            }
        });

        AtomicBoolean failed = new AtomicBoolean(false);
        CountDownLatch clientLatch = new CountDownLatch(3);
        client.newRequest(newURI(transport))
            .onResponseHeaders(response ->
            {
                if (response.getStatus() == HttpStatus.OK_200)
                    clientLatch.countDown();
            })
            .onResponseContent((response, content) ->
            {
                // System.err.println("Content: "+BufferUtil.toDetailString(content));
            })
            .onResponseFailure((response, failure) -> clientLatch.countDown())
            .send(result ->
            {
                failed.set(result.isFailed());
                clientLatch.countDown();
            });

        assertTrue(complete.await(10, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(10, TimeUnit.SECONDS));
        assertTrue(failed.get());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testIsReadyAtEOF(Transport transport) throws Exception
    {
        String text = "TEST\n";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                assertScope();
                response.flushBuffer();

                AsyncContext async = request.startAsync();
                ServletInputStream input = request.getInputStream();
                ServletOutputStream output = response.getOutputStream();

                input.setReadListener(new ReadListener()
                {
                    transient int _i = 0;
                    transient boolean _minusOne = false;
                    transient boolean _finished = false;

                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        assertScope();
                        while (input.isReady() && !input.isFinished())
                        {
                            int b = input.read();
                            if (b == -1)
                                _minusOne = true;
                            else if (data[_i++] != b)
                                throw new IllegalStateException();
                        }

                        if (input.isFinished())
                            _finished = true;
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        assertScope();
                        output.write(String.format("i=%d eof=%b finished=%b", _i, _minusOne, _finished).getBytes(StandardCharsets.UTF_8));
                        async.complete();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        t.printStackTrace();
                        async.complete();
                    }
                });
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE))
            .body(new StringRequestContent(text))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        String responseContent = response.getContentAsString();
        assertThat(responseContent, containsString("i=" + data.length + " eof=true finished=true"));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testOnAllDataRead(Transport transport) throws Exception
    {
        String success = "SUCCESS";
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                assertScope();
                response.flushBuffer();

                AsyncContext async = request.startAsync();
                async.setTimeout(5000);
                ServletInputStream in = request.getInputStream();
                ServletOutputStream out = response.getOutputStream();

                in.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {
                        assertScope();
                        try
                        {
                            sleep(1000);
                            if (!in.isReady())
                                throw new IllegalStateException();
                            if (in.read() != 'X')
                                throw new IllegalStateException();
                            if (!in.isReady())
                                throw new IllegalStateException();
                            if (in.read() != -1)
                                throw new IllegalStateException();
                        }
                        catch (IOException x)
                        {
                            throw new UncheckedIOException(x);
                        }
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        assertScope();
                        out.write(success.getBytes(StandardCharsets.UTF_8));
                        async.complete();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        t.printStackTrace();
                        async.complete();
                    }
                });
            }
        });

        byte[] data = "X".getBytes(StandardCharsets.UTF_8);
        CountDownLatch clientLatch = new CountDownLatch(1);
        AsyncRequestContent content = new AsyncRequestContent()
        {
            @Override
            public long getLength()
            {
                return data.length;
            }
        };
        client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(content)
            .timeout(5, TimeUnit.SECONDS)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    if (result.isSucceeded())
                    {
                        Response response = result.getResponse();
                        String content = getContentAsString();
                        if (response.getStatus() == HttpStatus.OK_200 && success.equals(content))
                            clientLatch.countDown();
                    }
                }
            });

        sleep(100);
        content.write(ByteBuffer.wrap(data), Callback.NOOP);
        content.close();

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testOtherThreadOnAllDataRead(Transport transport) throws Exception
    {
        String success = "SUCCESS";
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                assertScope();
                response.flushBuffer();

                AsyncContext async = request.startAsync();
                async.setTimeout(0);
                ServletInputStream input = request.getInputStream();
                ServletOutputStream output = response.getOutputStream();

                if (request.getDispatcherType() == DispatcherType.ERROR)
                    throw new IllegalStateException();

                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {
                        assertScope();
                        async.start(() ->
                        {
                            assertScope();
                            try
                            {
                                sleep(1000);
                                if (!input.isReady())
                                    throw new IllegalStateException();
                                if (input.read() != 'X')
                                    throw new IllegalStateException();
                                if (input.isReady())
                                {
                                    try
                                    {
                                        if (input.read() != -1)
                                            throw new IllegalStateException();
                                    }
                                    catch (IOException e)
                                    {
                                        // ignore
                                    }
                                }
                            }
                            catch (IOException x)
                            {
                                throw new UncheckedIOException(x);
                            }
                        });
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        output.write(success.getBytes(StandardCharsets.UTF_8));
                        async.complete();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        t.printStackTrace();
                        async.complete();
                    }
                });
            }
        });

        byte[] data = "X".getBytes(StandardCharsets.UTF_8);
        CountDownLatch clientLatch = new CountDownLatch(1);
        AsyncRequestContent content = new AsyncRequestContent();
        client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(content)
            .timeout(5, TimeUnit.SECONDS)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    if (result.isSucceeded())
                    {
                        Response response = result.getResponse();
                        String content = getContentAsString();
                        if (response.getStatus() == HttpStatus.OK_200 && success.equals(content))
                            clientLatch.countDown();
                    }
                }
            });

        sleep(100);
        content.write(ByteBuffer.wrap(data), Callback.NOOP);
        content.close();

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testCompleteBeforeOnAllDataRead(Transport transport) throws Exception
    {
        String success = "SUCCESS";

        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                assertScope();
                response.flushBuffer();

                AsyncContext async = request.startAsync();
                ServletInputStream input = request.getInputStream();
                ServletOutputStream output = response.getOutputStream();

                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        assertScope();
                        while (input.isReady())
                        {
                            int b = input.read();
                            if (b < 0)
                            {
                                output.write(success.getBytes(StandardCharsets.UTF_8));
                                async.complete();
                                return;
                            }
                        }
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        assertScope();
                        output.write("FAILURE".getBytes(StandardCharsets.UTF_8));
                        throw new IllegalStateException();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        t.printStackTrace();
                    }
                });
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE))
            .body(new StringRequestContent("XYZ"))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), Matchers.equalTo(success));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testEmptyAsyncRead(Transport transport) throws Exception
    {
        AtomicBoolean oda = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);

        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                assertScope();
                AsyncContext asyncContext = request.startAsync(request, response);
                response.setStatus(200);
                response.getOutputStream().close();
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {
                        assertScope();
                        oda.set(true);
                    }

                    @Override
                    public void onAllDataRead()
                    {
                        assertScope();
                        asyncContext.complete();
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        t.printStackTrace();
                        asyncContext.complete();
                    }
                });
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // onDataAvailable must not be called.
        assertFalse(oda.get());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testWriteFromOnDataAvailable(Transport transport) throws Exception
    {
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        CountDownLatch writeLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                AsyncContext asyncContext = request.startAsync();
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        ServletInputStream input = request.getInputStream();
                        ServletOutputStream output = response.getOutputStream();
                        while (input.isReady())
                        {
                            byte[] buffer = new byte[512];
                            int read = input.read(buffer);
                            if (read < 0)
                            {
                                asyncContext.complete();
                                break;
                            }
                            if (output.isReady())
                                output.write(buffer, 0, read);
                            else
                                fail("output is not ready?");
                        }
                    }

                    @Override
                    public void onAllDataRead()
                    {
                        asyncContext.complete();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        errors.offer(t);
                    }
                });
                response.getOutputStream().setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible()
                    {
                        writeLatch.countDown();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        errors.offer(t);
                    }
                });
            }
        });

        String content = "0123456789ABCDEF";
        AsyncRequestContent requestContent = new AsyncRequestContent();
        requestContent.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)), Callback.NOOP);
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(requestContent)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    if (result.isSucceeded())
                    {
                        Response response = result.getResponse();
                        assertThat(response.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
                        assertThat(getContentAsString(), Matchers.equalTo(content));
                        assertThat(errors, Matchers.hasSize(0));
                        clientLatch.countDown();
                    }
                }
            });

        assertTrue(writeLatch.await(5, TimeUnit.SECONDS));

        requestContent.close();

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testAsyncReadEarlyEOF(Transport transport) throws Exception
    {
        // SSLEngine receives the close alert from the client, and when
        // the server passes the response to encrypt and write, SSLEngine
        // only generates the close alert back, without encrypting the
        // response, so we need to skip the transports over TLS.
        Assumptions.assumeFalse(transport.isSecure());

        String content = "jetty";
        int responseCode = HttpStatus.NO_CONTENT_204;
        CountDownLatch readLatch = new CountDownLatch(content.length());
        CountDownLatch errorLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                AsyncContext asyncContext = request.startAsync();
                ServletInputStream input = request.getInputStream();
                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        while (input.isReady() && !input.isFinished())
                        {
                            input.read();
                            readLatch.countDown();
                        }
                    }

                    @Override
                    public void onAllDataRead()
                    {
                    }

                    @Override
                    public void onError(Throwable x)
                    {
                        response.setStatus(responseCode);
                        asyncContext.complete();
                        errorLatch.countDown();
                    }
                });
            }
        });

        CountDownLatch responseLatch = new CountDownLatch(1);
        AsyncRequestContent requestContent = new AsyncRequestContent();
        requestContent.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)), Callback.NOOP);
        var request = client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(requestContent)
            .onResponseSuccess(response ->
            {
                if (transport == Transport.HTTP || transport == Transport.UNIX_DOMAIN)
                    responseLatch.countDown();
            })
            .onResponseFailure((response, failure) ->
            {
                if (transport == Transport.H2C)
                    responseLatch.countDown();
            });

        Destination destination = client.resolveDestination(request);
        FuturePromise<org.eclipse.jetty.client.api.Connection> promise = new FuturePromise<>();
        destination.newConnection(promise);
        org.eclipse.jetty.client.api.Connection connection = promise.get(5, TimeUnit.SECONDS);
        CountDownLatch clientLatch = new CountDownLatch(1);
        connection.send(request, result ->
        {
            switch (transport)
            {
                case HTTP:
                case UNIX_DOMAIN:
                    assertThat(result.getResponse().getStatus(), Matchers.equalTo(responseCode));
                    break;
                case H2C:
                    // HTTP/2 does not attempt to write a response back, just a RST_STREAM.
                    assertTrue(result.isFailed());
                    break;
                default:
                    fail("Unhandled transport: " + transport);
            }
            clientLatch.countDown();
        });

        assertTrue(readLatch.await(5, TimeUnit.SECONDS));

        switch (transport)
        {
            case HTTP:
            case UNIX_DOMAIN:
                ((HttpConnectionOverHTTP)connection).getEndPoint().shutdownOutput();
                break;
            case H2C:
                // In case of HTTP/2, we not only send the request, but also the preface and
                // SETTINGS frames. SETTINGS frame need to be replied, so we want to wait to
                // write the reply before shutting output down, so that the test does not fail.
                Thread.sleep(1000);
                Session session = ((HttpConnectionOverHTTP2)connection).getSession();
                ((HTTP2Session)session).getEndPoint().shutdownOutput();
                break;
            default:
                fail("Unhandled transport: " + transport);
        }

        // Wait for the response to arrive before finishing the request.
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
        requestContent.close();

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testAsyncEcho(Transport transport) throws Exception
    {
        // TODO: investigate why H3 does not work.
        Assumptions.assumeTrue(transport != Transport.H3);

        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                System.err.println("service " + request);

                AsyncContext asyncContext = request.startAsync();
                ServletInputStream input = request.getInputStream();
                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        while (input.isReady())
                        {
                            int b = input.read();
                            if (b >= 0)
                            {
                                // System.err.printf("0x%2x %s %n", b, Character.isISOControl(b)?"?":(""+(char)b));
                                response.getOutputStream().write(b);
                            }
                            else
                                return;
                        }
                    }

                    @Override
                    public void onAllDataRead()
                    {
                        asyncContext.complete();
                    }

                    @Override
                    public void onError(Throwable x)
                    {
                    }
                });
            }
        });

        AsyncRequestContent contentProvider = new AsyncRequestContent();
        CountDownLatch clientLatch = new CountDownLatch(1);

        AtomicReference<Result> resultRef = new AtomicReference<>();
        client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(contentProvider)
            .send(new BufferingResponseListener(16 * 1024 * 1024)
            {
                @Override
                public void onComplete(Result result)
                {
                    resultRef.set(result);
                    clientLatch.countDown();
                }
            });

        for (int i = 0; i < 1_000_000; i++)
        {
            contentProvider.write(BufferUtil.toBuffer("S" + i), Callback.NOOP);
        }
        contentProvider.close();

        assertTrue(clientLatch.await(30, TimeUnit.SECONDS));
        assertThat(resultRef.get().isSucceeded(), Matchers.is(true));
        assertThat(resultRef.get().getResponse().getStatus(), Matchers.equalTo(HttpStatus.OK_200));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testWriteListenerFromOtherThread(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                request.getInputStream().setReadListener(new Listener(asyncContext));
            }
        });

        int cores = 4;
        int iterations = 10;
        CountDownLatch latch = new CountDownLatch(cores * iterations);
        Deque<Throwable> failures = new LinkedBlockingDeque<>();
        for (int i = 0; i < cores; ++i)
        {
            client.getExecutor().execute(() ->
            {
                for (int j = 0; j < iterations; ++j)
                {
                    try
                    {
                        ContentResponse response = client.newRequest(newURI(transport))
                            .method(HttpMethod.POST)
                            .body(new InputStreamRequestContent(new ByteArrayInputStream(new byte[16 * 1024])
                            {
                                @Override
                                public int read(byte[] b, int off, int len)
                                {
                                    sleep(5);
                                    return super.read(b, off, Math.min(len, 4242));
                                }
                            }))
                            .send();
                        assertEquals(HttpStatus.OK_200, response.getStatus());
                    }
                    catch (Throwable x)
                    {
                        failures.offer(x);
                    }
                    finally
                    {
                        latch.countDown();
                    }
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertThat(failures, empty());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testClientDefersContentServerIdleTimeout(Transport transport) throws Exception
    {
        CountDownLatch dataLatch = new CountDownLatch(1);
        CountDownLatch errorLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {
                        dataLatch.countDown();
                    }

                    @Override
                    public void onAllDataRead()
                    {
                        dataLatch.countDown();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        errorLatch.countDown();
                        response.setStatus(HttpStatus.REQUEST_TIMEOUT_408);
                        asyncContext.complete();
                    }
                });
            }
        });
        long idleTimeout = 1000;
        setStreamIdleTimeout(idleTimeout);

        CountDownLatch latch = new CountDownLatch(1);
        byte[] bytes = "[{\"key\":\"value\"}]".getBytes(StandardCharsets.UTF_8);
        OutputStreamRequestContent content = new OutputStreamRequestContent("application/json;charset=UTF-8")
        {
            @Override
            public long getLength()
            {
                return bytes.length;
            }
        };
        client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(content)
            .onResponseSuccess(response ->
            {
                Assertions.assertEquals(HttpStatus.REQUEST_TIMEOUT_408, response.getStatus());
                latch.countDown();
            })
            .send(null);

        // Wait for the server to idle timeout.
        Thread.sleep(2 * idleTimeout);

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));

        // Do not send the content to the server.

        assertFalse(dataLatch.await(1, TimeUnit.SECONDS));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private static class Listener implements ReadListener, WriteListener
    {
        private final Executor executor = Executors.newFixedThreadPool(32);
        private final CompletableFuture<?> inputComplete = new CompletableFuture<>();
        private final CompletableFuture<?> outputComplete = new CompletableFuture<>();
        private final AtomicBoolean responseWritten = new AtomicBoolean();
        private final AsyncContext asyncContext;
        private final HttpServletResponse response;
        private final ServletInputStream input;
        private final ServletOutputStream output;

        public Listener(AsyncContext asyncContext) throws IOException
        {
            this.asyncContext = asyncContext;
            this.response = (HttpServletResponse)asyncContext.getResponse();
            this.input = asyncContext.getRequest().getInputStream();
            this.output = response.getOutputStream();
            CompletableFuture.allOf(inputComplete, outputComplete)
                .whenComplete((ignoredResult, ignoredThrowable) -> asyncContext.complete());
            // Dispatch setting the write listener to another thread.
            executor.execute(() -> output.setWriteListener(this));
        }

        @Override
        public void onDataAvailable() throws IOException
        {
            byte[] buffer = new byte[16 * 1024];
            while (input.isReady())
            {
                if (input.read(buffer) < 0)
                    return;
            }
        }

        @Override
        public void onAllDataRead()
        {
            inputComplete.complete(null);
        }

        @Override
        public void onWritePossible()
        {
            // Dispatch OWP to another thread.
            executor.execute(() ->
            {
                while (output.isReady())
                {
                    if (responseWritten.compareAndSet(false, true))
                    {
                        try
                        {
                            response.setStatus(HttpServletResponse.SC_OK);
                            response.setContentType("text/plain;charset=utf-8");
                            output.write("Hello world".getBytes());
                        }
                        catch (IOException x)
                        {
                            throw new UncheckedIOException(x);
                        }
                    }
                    else
                    {
                        outputComplete.complete(null);
                        return;
                    }
                }
            });
        }

        @Override
        public void onError(Throwable t)
        {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            asyncContext.complete();
        }
    }
}
