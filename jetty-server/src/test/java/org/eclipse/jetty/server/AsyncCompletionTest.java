//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.ChannelEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Extended Server Tester.
 */
public class AsyncCompletionTest extends HttpServerTestFixture
{
    private static final Exchanger<PendingCallback> X = new Exchanger<>();
    private static final AtomicBoolean __complete = new AtomicBoolean();
    private static String __data = "Now is the time for all good men to come to the aid of the party";


    private static class PendingCallback extends Callback.Nested
    {
        private CompletableFuture<Void> _pending = new CompletableFuture<>();

        public PendingCallback(Callback callback)
        {
            super(callback);
        }

        @Override
        public void succeeded()
        {
            _pending.complete(null);
        }

        @Override
        public void failed(Throwable x)
        {
            _pending.completeExceptionally(x);
        }

        public void proceed()
        {
            try
            {
                _pending.get(10, TimeUnit.SECONDS);
                getCallback().succeeded();
            }
            catch (Throwable th)
            {
                th.printStackTrace();
                getCallback().failed(th);
            }
        }
    }

    @BeforeEach
    public void init() throws Exception
    {
        __complete.set(false);

        startServer(new ServerConnector(_server, new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                return configure(new ExtendedHttpConnection(getHttpConfiguration(), connector, endPoint), connector, endPoint);
            }
        })
        {
            @Override
            protected ChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
            {
                return new ExtendedEndPoint(channel, selectSet, key, getScheduler());
            }
        });
    }

    private static class ExtendedEndPoint extends SocketChannelEndPoint
    {
        public ExtendedEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
        {
            super(channel, selector, key, scheduler);
        }

        @Override
        public void write(Callback callback, ByteBuffer... buffers) throws IllegalStateException
        {
            PendingCallback delay = new PendingCallback(callback);
            super.write(delay, buffers);
            try
            {
                X.exchange(delay);
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    private static class ExtendedHttpConnection extends HttpConnection
    {
        public ExtendedHttpConnection(HttpConfiguration config, Connector connector, EndPoint endPoint)
        {
            super(config, connector, endPoint, HttpCompliance.RFC7230_LEGACY, false);
        }

        @Override
        public void onCompleted()
        {
            __complete.compareAndSet(false,true);
            super.onCompleted();
        }
    }

    // Tests from here use these parameters
    public static Stream<Arguments> tests()
    {
        List<Object[]> tests = new ArrayList<>();
        tests.add(new Object[]{new HelloWorldHandler(), false, 200, "Hello world"});
        tests.add(new Object[]{new SendErrorHandler(499, "Test async sendError"), false, 499, "Test async sendError"});
        tests.add(new Object[]{new AsyncReadyCompleteHandler(), false, 200, __data});
        tests.add(new Object[]{new AsyncWriteCompleteHandler(false, false), false, 200, __data});
        tests.add(new Object[]{new AsyncWriteCompleteHandler(false, true), false, 200, __data});
        tests.add(new Object[]{new AsyncWriteCompleteHandler(true, false), false, 200, __data});
        tests.add(new Object[]{new AsyncWriteCompleteHandler(true, true), false, 200, __data});
        tests.add(new Object[]{new BlockingWriteCompleteHandler(false, false, false), true, 200, __data});
        tests.add(new Object[]{new BlockingWriteCompleteHandler(false, false, true), true, 200, __data});
        tests.add(new Object[]{new BlockingWriteCompleteHandler(false, true, false), true, 200, __data});
        tests.add(new Object[]{new BlockingWriteCompleteHandler(false, true, true), true, 200, __data});
        tests.add(new Object[]{new BlockingWriteCompleteHandler(true, false, false), true, 200, __data});
        tests.add(new Object[]{new BlockingWriteCompleteHandler(true, false, true), true, 200, __data});
        tests.add(new Object[]{new BlockingWriteCompleteHandler(true, true, false), true, 200, __data});
        tests.add(new Object[]{new BlockingWriteCompleteHandler(true, true, true), true, 200, __data});
        tests.add(new Object[]{new SendContentHandler(false), false, 200, __data});
        tests.add(new Object[]{new SendContentHandler(true), true, 200, __data});
        return tests.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("tests")
    public void testAsyncCompletion(Handler handler, boolean blocked, int status, String message) throws Exception
    {
        configureServer(handler);

        int base = _threadPool.getBusyThreads();
        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            // write the request
            os.write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // The write should happen but the callback is delayed
            HttpTester.Response response = HttpTester.parseResponse(client.getInputStream());
            assertThat(response, Matchers.notNullValue());
            assertThat(response.getStatus(), is(status));
            String content = response.getContent();
            assertThat(content, containsString(message));

            // Check that a thread is held busy in write
            assertThat(_threadPool.getBusyThreads(), Matchers.greaterThan(base));  // TODO why is this the case for async?

            // Getting the Delayed callback will free the thread
            PendingCallback delay = X.exchange(null, 10, TimeUnit.SECONDS);

            // wait for threads to return to base level
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (_threadPool.getBusyThreads() != base + (blocked ? 1 : 0))
            {
                if (System.nanoTime() > end)
                    throw new TimeoutException();
                Thread.sleep(10);
            }

            // We are now asynchronously waiting!
            assertThat(__complete.get(), is(false));

            // Do we need to wait for an unready state?
            if (handler instanceof AsyncWriteCompleteHandler)
            {
                AsyncWriteCompleteHandler awch = (AsyncWriteCompleteHandler)handler;
                if (awch._unReady)
                    assertThat(awch._unReadySeen.await(5, TimeUnit.SECONDS),is(true));
            }

            // proceed with the completion
            delay.proceed();

            while(!__complete.get())
            {
                if (System.nanoTime() > end)
                    throw new TimeoutException();
                try
                {
                    X.exchange(null, 10, TimeUnit.MILLISECONDS).proceed();
                }
                catch (TimeoutException e)
                {}
            }
        }
    }

    private static class AsyncReadyCompleteHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            // start async
            // register WriteListener
            // if ready write bytes
            // if ready complete
            baseRequest.setHandled(true);
            AsyncContext context = request.startAsync();
            ServletOutputStream out = response.getOutputStream();
            out.setWriteListener(new WriteListener()
            {
                byte[] bytes = __data.getBytes(StandardCharsets.ISO_8859_1);

                @Override
                public void onWritePossible() throws IOException
                {
                    while (out.isReady())
                    {
                        if (bytes != null)
                        {
                            response.setContentType("text/plain");
                            response.setContentLength(bytes.length);
                            out.write(bytes);
                            bytes = null;
                        }
                        else
                        {
                            context.complete();
                            return;
                        }
                    }
                }

                @Override
                public void onError(Throwable t)
                {
                    t.printStackTrace();
                }
            });
        }
    }

    private static class AsyncWriteCompleteHandler extends AbstractHandler
    {
        final boolean _unReady;
        final boolean _close;
        final CountDownLatch _unReadySeen = new CountDownLatch(1);
        boolean _written;

        AsyncWriteCompleteHandler(boolean unReady, boolean close)
        {
            _unReady = unReady;
            _close = close;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            // start async
            // register WriteListener
            // if ready
            //   if not written write bytes
            //   if _unReady check that isReady() returns false and return
            //   if _close then call close without checking isReady()
            //   context.complete() without checking is ready
            baseRequest.setHandled(true);
            AsyncContext context = request.startAsync();
            ServletOutputStream out = response.getOutputStream();
            out.setWriteListener(new WriteListener() {
                byte[] bytes = __data.getBytes(StandardCharsets.ISO_8859_1);
                @Override
                public void onWritePossible() throws IOException
                {
                    if (out.isReady())
                    {
                        if (!_written)
                        {
                            _written = true;
                            response.setContentType("text/plain");
                            response.setContentLength(bytes.length);
                            out.write(bytes);
                        }
                        if (_unReady && _unReadySeen.getCount() == 1)
                        {
                            assertThat(out.isReady(), Matchers.is(false));
                            _unReadySeen.countDown();
                            return;
                        }
                        if (_close)
                        {
                            out.close();
                        }
                        context.complete();
                    }
                }

                @Override
                public void onError(Throwable t)
                {
                    t.printStackTrace();
                }
            });
        }

        @Override
        public String toString()
        {
            return String.format("AWCH@%x{ur=%b,c=%b}", hashCode(), _unReady, _close);
        }
    }

    private static class BlockingWriteCompleteHandler extends AbstractHandler
    {
        final boolean _contentLength;
        final boolean _close;
        final boolean _dispatchComplete;

        BlockingWriteCompleteHandler(boolean contentLength, boolean close, boolean dispatchComplete)
        {
            _contentLength = contentLength;
            _close = close;
            _dispatchComplete = dispatchComplete;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            // Start async
            // Do a blocking write in another thread
            // call complete while the write is still blocking
            baseRequest.setHandled(true);
            AsyncContext context = request.startAsync();
            ServletOutputStream out = response.getOutputStream();
            CountDownLatch writing = new CountDownLatch(1);

            Runnable write = () ->
            {
                try
                {
                    byte[] bytes = __data.getBytes(StandardCharsets.ISO_8859_1);
                    response.setContentType("text/plain");
                    if (_contentLength)
                        response.setContentLength(bytes.length);
                    writing.countDown();

                    out.write(bytes);

                    if (_close)
                        out.close();
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            };

            Runnable complete = () ->
            {
                try
                {
                    writing.await(5, TimeUnit.SECONDS);
                    Thread.sleep(200);
                    context.complete();
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            };

            if (_dispatchComplete)
            {
                context.start(complete);
                write.run();
            }
            else
            {
                context.start(write);
                complete.run();
            }
        }

        @Override
        public String toString()
        {
            return String.format("BWCH@%x{cl=%b,c=%b,dc=%b}", hashCode(), _contentLength, _close, _dispatchComplete);
        }
    }

    private static class SendContentHandler extends AbstractHandler
    {
        final boolean _blocking;

        private SendContentHandler(boolean blocking)
        {
            _blocking = blocking;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            // Start async
            // Do a blocking write in another thread
            // call complete while the write is still blocking
            baseRequest.setHandled(true);
            AsyncContext context = request.startAsync();
            ServletOutputStream out = response.getOutputStream();
            response.setContentType("text/plain");

            if (_blocking)
            {
                ((HttpOutput)out).sendContent(BufferUtil.toBuffer(__data));
                context.complete();
            }
            else
            {
                ((HttpOutput)out).sendContent(BufferUtil.toBuffer(__data), Callback.from(context::complete));
            }
        }

        @Override
        public String toString()
        {
            return String.format("SCH@%x{b=%b}", hashCode(), _blocking);
        }
    }
}
