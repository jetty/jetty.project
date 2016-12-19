//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
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
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.HttpInput.Content;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import static java.nio.ByteBuffer.wrap;
import static org.eclipse.jetty.util.BufferUtil.toArray;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class AsyncIOServletTest extends AbstractTest
{
    private static final ThreadLocal<RuntimeException> scope = new ThreadLocal<>();

    public AsyncIOServletTest(Transport transport)
    {
        super(transport == Transport.FCGI ? null : transport);
    }

    @Override
    protected void startServer(Handler handler) throws Exception
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
        Assert.assertNotNull("Not in scope", scope.get());
    }

    private void checkScope()
    {
        RuntimeException callScope = scope.get();
        if (callScope != null)
            throw callScope;
    }

    protected void stopServer() throws Exception
    {
        super.stopServer();
        checkScope();
        scope.set(null);
    }

    private void sleep(long ms) throws IOException
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            throw new InterruptedIOException();
        }
    }

    @Test
    public void testAsyncReadThrowsException() throws Exception
    {
        testAsyncReadThrows(new NullPointerException("explicitly_thrown_by_test"));
    }

    @Test
    public void testAsyncReadThrowsError() throws Exception
    {
        testAsyncReadThrows(new Error("explicitly_thrown_by_test"));
    }

    private void testAsyncReadThrows(Throwable throwable) throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
                    public void onAllDataRead() throws IOException
                    {
                        assertScope();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        Assert.assertThat("onError type", t, instanceOf(throwable.getClass()));
                        Assert.assertThat("onError message", t.getMessage(), is(throwable.getMessage()));
                        latch.countDown();
                        response.setStatus(500);
                        asyncContext.complete();
                    }
                });
            }
        });

        ContentResponse response = client.newRequest(newURI())
                .method(HttpMethod.POST)
                .path(servletPath)
                .content(new StringContentProvider("0123456789"))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
    }

    @Test
    public void testAsyncReadIdleTimeout() throws Exception
    {
        int status = 567;
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
                            inputStream.read();
                    }

                    @Override
                    public void onAllDataRead() throws IOException
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
        connector.setIdleTimeout(1000);
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
        DeferredContentProvider content = new DeferredContentProvider();
        content.offer(ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)));
        CountDownLatch responseLatch = new CountDownLatch(1);
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest(newURI())
                .method(HttpMethod.POST)
                .path(servletPath)
                .content(content)
                .onResponseSuccess(r -> responseLatch.countDown())
                .timeout(5, TimeUnit.SECONDS)
                .send(result ->
                {
                    assertEquals(status, result.getResponse().getStatus());
                    clientLatch.countDown();
                });

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
        content.close();
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testOnErrorThrows() throws Exception
    {
        AtomicInteger errors = new AtomicInteger();
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
                    public void onDataAvailable() throws IOException
                    {
                        assertScope();
                        throw new NullPointerException("explicitly_thrown_by_test_1");
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        assertScope();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        errors.incrementAndGet();
                        throw new NullPointerException("explicitly_thrown_by_test_2")
                        {{
                            this.initCause(t);
                        }};
                    }
                });
            }
        });

        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            ContentResponse response = client.newRequest(newURI())
                    .path(servletPath)
                    .content(new StringContentProvider("0123456789"))
                    .timeout(5, TimeUnit.SECONDS)
                    .send();

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
            Assert.assertEquals(1, errors.get());
        }
    }

    @Test
    public void testAsyncWriteThrowsException() throws Exception
    {
        testAsyncWriteThrows(new NullPointerException("explicitly_thrown_by_test"));
    }

    @Test
    public void testAsyncWriteThrowsError() throws Exception
    {
        testAsyncWriteThrows(new Error("explicitly_thrown_by_test"));
    }

    private void testAsyncWriteThrows(Throwable throwable) throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
                        Assert.assertSame(throwable, t);
                    }
                });
            }
        });

        ContentResponse response = client.newRequest(newURI())
                .path(servletPath)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
    }

    @Test
    public void testAsyncWriteClosed() throws Exception
    {
        String text = "Now is the winter of our discontent. How Now Brown Cow. The quick brown fox jumped over the lazy dog.\n";
        for (int i = 0; i < 10; i++)
            text = text + text;
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        CountDownLatch errorLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                assertScope();
                response.flushBuffer();

                AsyncContext async = request.startAsync();
                ServletOutputStream out = response.getOutputStream();
                out.setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        assertScope();

                        // Wait for the failure to arrive to
                        // the server while we are about to write.
                        sleep(1000);

                        out.write(data);
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
        client.newRequest(newURI())
                .path(servletPath)
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

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAsyncWriteLessThanContentLengthFlushed() throws Exception
    {
        CountDownLatch complete = new CountDownLatch(1);
        start(new HttpServlet()
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
                        while(true)
                        {
                            if (!out.isReady())
                                return;
                            
                            switch(state.get())
                            {
                                case 0:
                                    state.incrementAndGet();
                                    WriteListener listener = this;
                                    new Thread(()->
                                    {
                                        try
                                        {
                                            Thread.sleep(50);
                                            listener.onWritePossible();
                                        }
                                        catch(Exception e)
                                        {}
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
        client.newRequest(newURI())
                .path(servletPath)
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

    @Test
    public void testIsReadyAtEOF() throws Exception
    {
        String text = "TEST\n";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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

        ContentResponse response = client.newRequest(newURI())
                .method(HttpMethod.POST)
                .path(servletPath)
                .header(HttpHeader.CONNECTION, "close")
                .content(new StringContentProvider(text))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        String responseContent = response.getContentAsString();
        assertThat(responseContent, containsString("i=" + data.length + " eof=true finished=true"));
    }

    @Test
    public void testOnAllDataRead() throws Exception
    {
        String success = "SUCCESS";
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
                    public void onDataAvailable() throws IOException
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
        DeferredContentProvider content = new DeferredContentProvider()
        {
            @Override
            public long getLength()
            {
                return data.length;
            }
        };
        client.newRequest(newURI())
                .method(HttpMethod.POST)
                .path(servletPath)
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

    @Test
    public void testOtherThreadOnAllDataRead() throws Exception
    {
        String success = "SUCCESS";
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
                    public void onDataAvailable() throws IOException
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
                        assertScope();
                        t.printStackTrace();
                        async.complete();
                    }
                });
            }
        });

        byte[] data = "X".getBytes(StandardCharsets.UTF_8);
        CountDownLatch clientLatch = new CountDownLatch(1);
        DeferredContentProvider content = new DeferredContentProvider();
        client.newRequest(newURI())
                .method(HttpMethod.POST)
                .path(servletPath)
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

    @Test
    public void testCompleteBeforeOnAllDataRead() throws Exception
    {
        String success = "SUCCESS";
        AtomicBoolean allDataRead = new AtomicBoolean(false);

        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
                        allDataRead.set(true);
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

        ContentResponse response = client.newRequest(newURI())
                .method(HttpMethod.POST)
                .path(servletPath)
                .header(HttpHeader.CONNECTION, "close")
                .content(new StringContentProvider("XYZ"))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        assertThat(response.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), Matchers.equalTo(success));
    }

    @Test
    public void testEmptyAsyncRead() throws Exception
    {
        AtomicBoolean oda = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);

        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                assertScope();
                AsyncContext asyncContext = request.startAsync(request, response);
                response.setStatus(200);
                response.getOutputStream().close();
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        assertScope();
                        oda.set(true);
                    }

                    @Override
                    public void onAllDataRead() throws IOException
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

        ContentResponse response = client.newRequest(newURI())
                .path(servletPath)
                .header(HttpHeader.CONNECTION, "close")
                .timeout(5, TimeUnit.SECONDS)
                .send();

        assertThat(response.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // onDataAvailable must not be called.
        Assert.assertFalse(oda.get());
    }

    @Test
    public void testWriteFromOnDataAvailable() throws Exception
    {
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        CountDownLatch writeLatch = new CountDownLatch(1);
        start(new HttpServlet()
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
                                Assert.fail();
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
        client.newRequest(newURI())
                .method(HttpMethod.POST)
                .path(servletPath)
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

    @Test
    public void testAsyncReadEarlyEOF() throws Exception
    {
        // SSLEngine receives the close alert from the client, and when
        // the server passes the response to encrypt and write, SSLEngine
        // only generates the close alert back, without encrypting the
        // response, so we need to skip the transports over TLS.
        Assume.assumeThat(transport, Matchers.not(Matchers.isOneOf(Transport.HTTPS, Transport.H2)));

        String content = "jetty";
        int responseCode = HttpStatus.NO_CONTENT_204;
        CountDownLatch readLatch = new CountDownLatch(content.length());
        CountDownLatch errorLatch = new CountDownLatch(1);
        start(new HttpServlet()
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
                    public void onAllDataRead() throws IOException
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
        org.eclipse.jetty.client.api.Request request = client.newRequest(newURI())
                .method(HttpMethod.POST)
                .path(servletPath)
                .content(contentProvider)
                .onResponseSuccess(response -> responseLatch.countDown());

        Destination destination = client.getDestination(getScheme(), "localhost", connector.getLocalPort());
        FuturePromise<org.eclipse.jetty.client.api.Connection> promise = new FuturePromise<>();
        destination.newConnection(promise);
        org.eclipse.jetty.client.api.Connection connection = promise.get(5, TimeUnit.SECONDS);
        CountDownLatch clientLatch = new CountDownLatch(1);
        connection.send(request, result ->
        {
            assertThat(result.getResponse().getStatus(), Matchers.equalTo(responseCode));
            clientLatch.countDown();
        });

        assertTrue(readLatch.await(5, TimeUnit.SECONDS));

        switch (transport)
        {
            case HTTP:
            case HTTPS:
                ((HttpConnectionOverHTTP)connection).getEndPoint().shutdownOutput();
                break;
            case H2C:
            case H2:
                Session session = ((HttpConnectionOverHTTP2)connection).getSession();
                ((HTTP2Session)session).getEndPoint().shutdownOutput();
                break;
            default:
                Assert.fail();
        }

        // Wait for the response to arrive before finishing the request.
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
        contentProvider.close();

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }
    

    @Test
    public void testAsyncIntercepted() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {                    
                System.err.println("Service "+request);

                final HttpInput httpInput = ((Request)request).getHttpInput();
                httpInput.addInterceptor(new HttpInput.Interceptor()
                {
                    int state = 0;
                    Content saved;
                    
                    @Override
                    public Content readFrom(Content content)
                    {
                        // System.err.printf("readFrom s=%d saved=%b %s%n",state,saved!=null,content);
                        switch(state)
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
                                int l = content.get(b,0,1);
                                return new Content(wrap(b,0,l));
                                
                            case 3: 
                            {
                                // double vision
                                if (content.isEmpty())
                                {
                                    if (saved==null)
                                    {
                                        state++;
                                        return content;
                                    }
                                    Content copy = saved;
                                    saved=null;
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
                        while (input.isReady() && !input.isFinished())
                        {
                            int b = input.read();
                            if (b>0)
                            {
                                // System.err.printf("0x%2x %s %n", b, Character.isISOControl(b)?"?":(""+(char)b));
                                out.write(b);
                            }
                            else
                                onAllDataRead();
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

        client.newRequest(newURI())
                .method(HttpMethod.POST)
                .path(servletPath)
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
        
        
        Assert.assertTrue(clientLatch.await(10,TimeUnit.SECONDS));
        

    }
        
}
