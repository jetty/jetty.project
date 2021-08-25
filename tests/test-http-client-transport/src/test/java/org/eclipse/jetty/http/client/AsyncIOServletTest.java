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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.http.HttpConnectionOverHTTP2;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpInput.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static java.nio.ByteBuffer.wrap;
import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.http.client.Transport.FCGI;
import static org.eclipse.jetty.http.client.Transport.H2C;
import static org.eclipse.jetty.http.client.Transport.HTTP;
import static org.eclipse.jetty.util.BufferUtil.toArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class AsyncIOServletTest extends AbstractTest<AsyncIOServletTest.AsyncTransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        // Skip FCGI for now, not much interested in its server-side behavior.
        Assumptions.assumeTrue(transport != FCGI);
        setScenario(new AsyncTransportScenario(transport));
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
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncReadThrowsException(Transport transport) throws Exception
    {
        init(transport);
        testAsyncReadThrows(new NullPointerException("explicitly_thrown_by_test"));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncReadThrowsError(Transport transport) throws Exception
    {
        init(transport);
        testAsyncReadThrows(new Error("explicitly_thrown_by_test"));
    }

    private void testAsyncReadThrows(Throwable throwable) throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                scenario.assertScope();
                AsyncContext asyncContext = request.startAsync(request, response);
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        scenario.assertScope();
                        if (throwable instanceof RuntimeException)
                            throw (RuntimeException)throwable;
                        if (throwable instanceof Error)
                            throw (Error)throwable;
                        throw new IOException(throwable);
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        scenario.assertScope();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        scenario.assertScope();
                        assertThat("onError type", t, instanceOf(throwable.getClass()));
                        assertThat("onError message", t.getMessage(), is(throwable.getMessage()));
                        latch.countDown();
                        response.setStatus(500);
                        asyncContext.complete();
                    }
                });
            }
        });

        ContentResponse response = scenario.client.newRequest(scenario.newURI())
            .method(HttpMethod.POST)
            .path(scenario.servletPath)
            .content(new StringContentProvider("0123456789"))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncReadIdleTimeout(Transport transport) throws Exception
    {
        init(transport);
        int status = 567;
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                scenario.assertScope();
                AsyncContext asyncContext = request.startAsync(request, response);
                asyncContext.setTimeout(0);
                ServletInputStream inputStream = request.getInputStream();
                inputStream.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        scenario.assertScope();
                        while (inputStream.isReady() && !inputStream.isFinished())
                        {
                            inputStream.read();
                        }
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        scenario.assertScope();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        scenario.assertScope();
                        response.setStatus(status);
                        // Do not put Connection: close header here, the test
                        // verifies that the server closes no matter what.
                        asyncContext.complete();
                    }
                });
            }
        });
        scenario.setConnectionIdleTimeout(1000);
        CountDownLatch closeLatch = new CountDownLatch(1);
        scenario.connector.addBean(new Connection.Listener()
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
        DeferredContentProvider content = new DeferredContentProvider();
        content.offer(ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)));
        CountDownLatch responseLatch = new CountDownLatch(1);
        CountDownLatch clientLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .method(HttpMethod.POST)
            .path(scenario.servletPath)
            .content(content)
            .onResponseSuccess(r -> responseLatch.countDown())
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                assertEquals(status, result.getResponse().getStatus());
                clientLatch.countDown();
            });

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "close latch expired");
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS), "response latch expired");
        content.close();
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS), "client latch expired");
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testOnErrorThrows(Transport transport) throws Exception
    {
        init(transport);
        AtomicInteger errors = new AtomicInteger();
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                scenario.assertScope();
                if (request.getDispatcherType() == DispatcherType.ERROR)
                {
                    response.flushBuffer();
                    return;
                }

                request.startAsync(request, response);
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        scenario.assertScope();
                        throw new NullPointerException("explicitly_thrown_by_test_1");
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        scenario.assertScope();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        scenario.assertScope();
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

        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            ContentResponse response = scenario.client.newRequest(scenario.newURI())
                .path(scenario.servletPath)
                .content(new StringContentProvider("0123456789"))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
            assertEquals(1, errors.get());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncWriteThrowsException(Transport transport) throws Exception
    {
        init(transport);
        testAsyncWriteThrows(new NullPointerException("explicitly_thrown_by_test"));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncWriteThrowsError(Transport transport) throws Exception
    {
        init(transport);
        testAsyncWriteThrows(new Error("explicitly_thrown_by_test"));
    }

    private void testAsyncWriteThrows(Throwable throwable) throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                scenario.assertScope();
                AsyncContext asyncContext = request.startAsync(request, response);
                response.getOutputStream().setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        scenario.assertScope();
                        if (throwable instanceof RuntimeException)
                            throw (RuntimeException)throwable;
                        if (throwable instanceof Error)
                            throw (Error)throwable;
                        throw new IOException(throwable);
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        scenario.assertScope();
                        latch.countDown();
                        response.setStatus(500);
                        asyncContext.complete();
                        assertSame(throwable, t);
                    }
                });
            }
        });

        ContentResponse response = scenario.client.newRequest(scenario.newURI())
            .path(scenario.servletPath)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncWriteClosed(Transport transport) throws Exception
    {
        init(transport);

        String text = "Now is the winter of our discontent. How Now Brown Cow. The quick brown fox jumped over the lazy dog.\n";
        for (int i = 0; i < 10; i++)
        {
            text = text + text;
        }
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        CountDownLatch errorLatch = new CountDownLatch(1);
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                scenario.assertScope();
                response.flushBuffer();

                AsyncContext async = request.startAsync();
                ServletOutputStream out = response.getOutputStream();
                out.setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        scenario.assertScope();

                        // Wait for the failure to arrive to
                        // the server while we are about to write.
                        try
                        {
                            await().atMost(5, TimeUnit.SECONDS).until(() ->
                            {
                                out.write(new byte[0]);
                                // Extract HttpOutput._apiState value from toString.
                                return !out.toString().split(",")[1].split("=")[1].equals("READY");
                            });
                        }
                        catch (Exception e)
                        {
                            throw new AssertionError(e);
                        }

                        out.write(data);
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        scenario.assertScope();
                        async.complete();
                        errorLatch.countDown();
                    }
                });
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .path(scenario.servletPath)
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
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncWriteLessThanContentLengthFlushed(Transport transport) throws Exception
    {
        init(transport);

        CountDownLatch complete = new CountDownLatch(1);
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
                                        catch (Exception e)
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
        scenario.client.newRequest(scenario.newURI())
            .path(scenario.servletPath)
            .onResponseHeaders(response ->
            {
                if (response.getStatus() == HttpStatus.OK_200)
                    clientLatch.countDown();
            })
            .onResponseContent(new Response.ContentListener()
            {
                @Override
                public void onContent(Response response, ByteBuffer content)
                {
                    // System.err.println("Content: "+BufferUtil.toDetailString(content));
                }
            })
            .onResponseFailure(new Response.FailureListener()
            {
                @Override
                public void onFailure(Response response, Throwable failure)
                {
                    clientLatch.countDown();
                }
            })
            .send(result ->
            {
                failed.set(result.isFailed());
                clientLatch.countDown();
                clientLatch.countDown();
                clientLatch.countDown();
            });

        assertTrue(complete.await(10, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(10, TimeUnit.SECONDS));
        assertTrue(failed.get());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testIsReadyAtEOF(Transport transport) throws Exception
    {
        init(transport);
        String text = "TEST\n";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                scenario.assertScope();
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
                        scenario.assertScope();
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
                        scenario.assertScope();
                        output.write(String.format("i=%d eof=%b finished=%b", _i, _minusOne, _finished).getBytes(StandardCharsets.UTF_8));
                        async.complete();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        scenario.assertScope();
                        t.printStackTrace();
                        async.complete();
                    }
                });
            }
        });

        ContentResponse response = scenario.client.newRequest(scenario.newURI())
            .method(HttpMethod.POST)
            .path(scenario.servletPath)
            .header(HttpHeader.CONNECTION, "close")
            .content(new StringContentProvider(text))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        String responseContent = response.getContentAsString();
        assertThat(responseContent, containsString("i=" + data.length + " eof=true finished=true"));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testOnAllDataRead(Transport transport) throws Exception
    {
        init(transport);
        String success = "SUCCESS";
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                scenario.assertScope();
                response.flushBuffer();

                AsyncContext async = request.startAsync();
                async.setTimeout(5000);
                ServletInputStream in = request.getInputStream();
                ServletOutputStream out = response.getOutputStream();

                in.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        scenario.assertScope();
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
                        scenario.assertScope();
                        out.write(success.getBytes(StandardCharsets.UTF_8));
                        async.complete();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        scenario.assertScope();
                        t.printStackTrace();
                        async.complete();
                    }
                });
            }
        });

        byte[] data = "X".getBytes(StandardCharsets.UTF_8);
        CountDownLatch clientLatch = new CountDownLatch(1);
        DeferredContentProvider content = new DeferredContentProvider()
        {
            @Override
            public long getLength()
            {
                return data.length;
            }
        };
        scenario.client.newRequest(scenario.newURI())
            .method(HttpMethod.POST)
            .path(scenario.servletPath)
            .content(content)
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
        content.offer(ByteBuffer.wrap(data));
        content.close();

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testOtherThreadOnAllDataRead(Transport transport) throws Exception
    {
        init(transport);
        String success = "SUCCESS";
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                scenario.assertScope();
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
                    public void onDataAvailable() throws IOException
                    {
                        scenario.assertScope();
                        async.start(() ->
                        {
                            scenario.assertScope();
                            try
                            {
                                sleep(1000);
                                if (!input.isReady())
                                    throw new IllegalStateException();
                                if (input.read() != 'X')
                                    throw new IllegalStateException();
                                if (!input.isReady())
                                    throw new IllegalStateException();
                                if (input.read() != -1)
                                    throw new IllegalStateException();
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
                        scenario.assertScope();
                        t.printStackTrace();
                        async.complete();
                    }
                });
            }
        });

        byte[] data = "X".getBytes(StandardCharsets.UTF_8);
        CountDownLatch clientLatch = new CountDownLatch(1);
        DeferredContentProvider content = new DeferredContentProvider();
        scenario.client.newRequest(scenario.newURI())
            .method(HttpMethod.POST)
            .path(scenario.servletPath)
            .content(content)
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
        content.offer(ByteBuffer.wrap(data));
        content.close();

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testCompleteBeforeOnAllDataRead(Transport transport) throws Exception
    {
        init(transport);
        String success = "SUCCESS";
        AtomicBoolean allDataRead = new AtomicBoolean(false);

        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                scenario.assertScope();
                response.flushBuffer();

                AsyncContext async = request.startAsync();
                ServletInputStream input = request.getInputStream();
                ServletOutputStream output = response.getOutputStream();

                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        scenario.assertScope();
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
                        scenario.assertScope();
                        output.write("FAILURE".getBytes(StandardCharsets.UTF_8));
                        allDataRead.set(true);
                        throw new IllegalStateException();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        scenario.assertScope();
                        t.printStackTrace();
                    }
                });
            }
        });

        ContentResponse response = scenario.client.newRequest(scenario.newURI())
            .method(HttpMethod.POST)
            .path(scenario.servletPath)
            .header(HttpHeader.CONNECTION, "close")
            .content(new StringContentProvider("XYZ"))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), Matchers.equalTo(success));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testEmptyAsyncRead(Transport transport) throws Exception
    {
        init(transport);
        AtomicBoolean oda = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);

        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                scenario.assertScope();
                AsyncContext asyncContext = request.startAsync(request, response);
                response.setStatus(200);
                response.getOutputStream().close();
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        scenario.assertScope();
                        oda.set(true);
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        scenario.assertScope();
                        asyncContext.complete();
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        scenario.assertScope();
                        t.printStackTrace();
                        asyncContext.complete();
                    }
                });
            }
        });

        ContentResponse response = scenario.client.newRequest(scenario.newURI())
            .path(scenario.servletPath)
            .header(HttpHeader.CONNECTION, "close")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // onDataAvailable must not be called.
        assertFalse(oda.get());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testWriteFromOnDataAvailable(Transport transport) throws Exception
    {
        init(transport);
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        CountDownLatch writeLatch = new CountDownLatch(1);
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
                    public void onAllDataRead() throws IOException
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
                    public void onWritePossible() throws IOException
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
        DeferredContentProvider contentProvider = new DeferredContentProvider();
        contentProvider.offer(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
        CountDownLatch clientLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .method(HttpMethod.POST)
            .path(scenario.servletPath)
            .content(contentProvider)
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

        contentProvider.close();

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncReadEarlyEOF(Transport transport) throws Exception
    {
        init(transport);

        // SSLEngine receives the close alert from the client, and when
        // the server passes the response to encrypt and write, SSLEngine
        // only generates the close alert back, without encrypting the
        // response, so we need to skip the transports over TLS.
        Assumptions.assumeFalse(scenario.transport.isTlsBased());
        Assumptions.assumeFalse(scenario.transport == FCGI);

        String content = "jetty";
        int responseCode = HttpStatus.NO_CONTENT_204;
        CountDownLatch readLatch = new CountDownLatch(content.length());
        CountDownLatch errorLatch = new CountDownLatch(1);
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
                            int read = input.read();
                            // System.err.printf("%x%n", read);
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
        DeferredContentProvider contentProvider = new DeferredContentProvider();
        contentProvider.offer(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
        org.eclipse.jetty.client.api.Request request = scenario.client.newRequest(scenario.newURI())
            .method(HttpMethod.POST)
            .path(scenario.servletPath)
            .content(contentProvider)
            .onResponseSuccess(response ->
            {
                if (transport == HTTP)
                    responseLatch.countDown();
            })
            .onResponseFailure((response, failure) ->
            {
                if (transport == H2C)
                    responseLatch.countDown();
            });

        Destination destination = scenario.client.getDestination(scenario.getScheme(),
            "localhost",
            scenario.getNetworkConnectorLocalPortInt().get());
        FuturePromise<org.eclipse.jetty.client.api.Connection> promise = new FuturePromise<>();
        destination.newConnection(promise);
        org.eclipse.jetty.client.api.Connection connection = promise.get(5, TimeUnit.SECONDS);
        CountDownLatch clientLatch = new CountDownLatch(1);
        connection.send(request, result ->
        {
            switch (transport)
            {
                case HTTP:
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
        contentProvider.close();

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncIntercepted(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                System.err.println("Service " + request);

                final HttpInput httpInput = ((Request)request).getHttpInput();
                httpInput.addInterceptor(new HttpInput.Interceptor()
                {
                    int state = 0;
                    Content saved;

                    @Override
                    public Content readFrom(Content content)
                    {
                        // System.err.printf("readFrom s=%d saved=%b %s%n",state,saved!=null,content);
                        switch (state)
                        {
                            case 0:
                                // null transform
                                if (content.isEmpty())
                                    state++;
                                return null;

                            case 1:
                            {
                                // copy transform
                                if (content.isEmpty())
                                {
                                    state++;
                                    return content;
                                }
                                ByteBuffer copy = wrap(toArray(content.getByteBuffer()));
                                content.skip(copy.remaining());
                                return new Content(copy);
                            }

                            case 2:
                                // byte by byte
                                if (content.isEmpty())
                                {
                                    state++;
                                    return content;
                                }
                                byte[] b = new byte[1];
                                int l = content.get(b, 0, 1);
                                return new Content(wrap(b, 0, l));

                            case 3:
                            {
                                // double vision
                                if (content.isEmpty())
                                {
                                    if (saved == null)
                                    {
                                        state++;
                                        return content;
                                    }
                                    Content copy = saved;
                                    saved = null;
                                    return copy;
                                }

                                byte[] data = toArray(content.getByteBuffer());
                                content.skip(data.length);
                                saved = new Content(wrap(data));
                                return new Content(wrap(data));
                            }

                            default:
                                return null;
                        }
                    }
                });

                AsyncContext asyncContext = request.startAsync();
                ServletInputStream input = request.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();

                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        while (input.isReady())
                        {
                            int b = input.read();
                            if (b > 0)
                            {
                                // System.err.printf("0x%2x %s %n", b, Character.isISOControl(b)?"?":(""+(char)b));
                                out.write(b);
                            }
                            else if (b < 0)
                                return;
                        }
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        response.getOutputStream().write(out.toByteArray());
                        asyncContext.complete();
                    }

                    @Override
                    public void onError(Throwable x)
                    {
                    }
                });
            }
        });

        DeferredContentProvider contentProvider = new DeferredContentProvider();
        CountDownLatch clientLatch = new CountDownLatch(1);

        String expected =
            "S0" +
                "S1" +
                "S2" +
                "S3S3" +
                "S4" +
                "S5" +
                "S6";

        scenario.client.newRequest(scenario.newURI())
            .method(HttpMethod.POST)
            .path(scenario.servletPath)
            .content(contentProvider)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    if (result.isSucceeded())
                    {
                        Response response = result.getResponse();
                        assertThat(response.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
                        assertThat(getContentAsString(), Matchers.equalTo(expected));
                        clientLatch.countDown();
                    }
                }
            });

        contentProvider.offer(BufferUtil.toBuffer("S0"));
        contentProvider.flush();
        contentProvider.offer(BufferUtil.toBuffer("S1"));
        contentProvider.flush();
        contentProvider.offer(BufferUtil.toBuffer("S2"));
        contentProvider.flush();
        contentProvider.offer(BufferUtil.toBuffer("S3"));
        contentProvider.flush();
        contentProvider.offer(BufferUtil.toBuffer("S4"));
        contentProvider.flush();
        contentProvider.offer(BufferUtil.toBuffer("S5"));
        contentProvider.flush();
        contentProvider.offer(BufferUtil.toBuffer("S6"));
        contentProvider.close();

        assertTrue(clientLatch.await(10, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testWriteListenerFromOtherThread(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
            scenario.client.getExecutor().execute(() ->
            {
                for (int j = 0; j < iterations; ++j)
                {
                    try
                    {
                        ContentResponse response = scenario.client.newRequest(scenario.newURI())
                            .method(HttpMethod.POST)
                            .path(scenario.servletPath)
                            .content(new InputStreamContentProvider(new ByteArrayInputStream(new byte[16 * 1024])
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
                        latch.countDown();
                    }
                    catch (Throwable x)
                    {
                        failures.offer(x);
                    }
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertTrue(failures.isEmpty());
    }

    private class Listener implements ReadListener, WriteListener
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
        public void onAllDataRead() throws IOException
        {
            inputComplete.complete(null);
        }

        @Override
        public void onWritePossible() throws IOException
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

    public static class AsyncTransportScenario extends TransportScenario
    {
        public static final ThreadLocal<RuntimeException> scope = new ThreadLocal<>();

        public AsyncTransportScenario(Transport transport) throws IOException
        {
            super(transport);
        }

        @Override
        public void startServer(Handler handler) throws Exception
        {
            if (handler == context)
            {
                // Add this listener before the context is started, so it's durable.
                context.addEventListener(new ContextHandler.ContextScopeListener()
                {
                    @Override
                    public void enterScope(Context context, Request request, Object reason)
                    {
                        checkScope();
                        scope.set(new RuntimeException());
                    }

                    @Override
                    public void exitScope(Context context, Request request)
                    {
                        assertScope();
                        scope.set(null);
                    }
                });
            }
            super.startServer(handler);
        }

        private void assertScope()
        {
            assertNotNull(scope.get(), "Not in scope");
        }

        private void checkScope()
        {
            RuntimeException callScope = scope.get();
            if (callScope != null)
                throw callScope;
        }

        @Override
        public void stopServer() throws Exception
        {
            checkScope();
            scope.set(null);
        }
    }
}
