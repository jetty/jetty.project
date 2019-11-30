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
            catch(Throwable th)
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
        tests.add(new Object[]{new HelloWorldHandler(), 200, "Hello world"});
        tests.add(new Object[]{new SendErrorHandler(499,"Test async sendError"), 499, "Test async sendError"});
        tests.add(new Object[]{new AsyncReadyCompleteHandler(), 200, __data});
        tests.add(new Object[]{new AsyncWriteCompleteHandler(false, false), 200, __data});
        tests.add(new Object[]{new AsyncWriteCompleteHandler(false, true), 200, __data});
        tests.add(new Object[]{new AsyncWriteCompleteHandler(true, false), 200, __data});
        tests.add(new Object[]{new AsyncWriteCompleteHandler(true, true), 200, __data});
        return tests.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("tests")
    public void testAsyncCompletion(Handler handler, int status, String message) throws Exception
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
            assertThat(_threadPool.getBusyThreads(), Matchers.greaterThan(base));

            // Getting the Delayed callback will free the thread
            PendingCallback delay = X.exchange(null, 10, TimeUnit.SECONDS);

            // wait for threads to return to base level
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while(_threadPool.getBusyThreads() != base)
            {
                if (System.nanoTime() > end)
                {
                    _threadPool.setDetailedDump(true);
                    _threadPool.dumpStdErr();
                    throw new TimeoutException();
                }
                Thread.sleep(10);
            }

            // We are now asynchronously waiting!
            assertThat(__complete.get(), is(false));

            // proceed with the completion
            delay.proceed();

            while(!__complete.get())
            {
                if (System.nanoTime() > end)
                    throw new TimeoutException();
                Thread.sleep(10);
            }
        }
    }

    private static class AsyncReadyCompleteHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            AsyncContext context = request.startAsync();
            ServletOutputStream out = response.getOutputStream();
            out.setWriteListener(new WriteListener() {
                byte[] bytes = __data.getBytes(StandardCharsets.ISO_8859_1);
                @Override
                public void onWritePossible() throws IOException
                {
                    while (out.isReady())
                    {
                        System.err.println("isReady "+ (bytes!=null));
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
                    System.err.println("!isReady ");
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

        AsyncWriteCompleteHandler(boolean unReady, boolean close)
        {
            _unReady = unReady;
            _close = close;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            AsyncContext context = request.startAsync();
            ServletOutputStream out = response.getOutputStream();
            out.setWriteListener(new WriteListener() {
                byte[] bytes = __data.getBytes(StandardCharsets.ISO_8859_1);
                @Override
                public void onWritePossible() throws IOException
                {
                    if (out.isReady())
                    {
                        response.setContentType("text/plain");
                        response.setContentLength(bytes.length);
                        out.write(bytes);
                        if (_unReady)
                            assertThat(out.isReady(),Matchers.is(false));
                        if (_close)
                            out.close();
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
    }
}
