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

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.ChannelEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SocketChannelEndPoint;
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
    private static final Exchanger<DelayedCallback> X = new Exchanger<>();
    private static final AtomicBoolean COMPLETE = new AtomicBoolean();

    private static class DelayedCallback extends Callback.Nested
    {
        private CompletableFuture<Void> _delay = new CompletableFuture<>();

        public DelayedCallback(Callback callback)
        {
            super(callback);
        }

        @Override
        public void succeeded()
        {
            _delay.complete(null);
        }

        @Override
        public void failed(Throwable x)
        {
            _delay.completeExceptionally(x);
        }

        public void proceed()
        {
            try
            {
                _delay.get(10, TimeUnit.SECONDS);
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
        COMPLETE.set(false);

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
            DelayedCallback delay = new DelayedCallback(callback);
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
            COMPLETE.compareAndSet(false,true);
            super.onCompleted();
        }
    }

    // Tests from here use these parameters
    public static Stream<Arguments> tests()
    {
        List<Object[]> tests = new ArrayList<>();
        tests.add(new Object[]{new HelloWorldHandler(), 200, "Hello world"});
        tests.add(new Object[]{new SendErrorHandler(499,"Test async sendError"), 499, "Test async sendError"});
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
            assertThat(response.getStatus(), is(status));
            String content = response.getContent();
            assertThat(content, containsString(message));

            // Check that a thread is held busy in write
            assertThat(_threadPool.getBusyThreads(), Matchers.greaterThan(base));

            // Getting the Delayed callback will free the thread
            DelayedCallback delay = X.exchange(null, 10, TimeUnit.SECONDS);

            // wait for threads to return to base level
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while(_threadPool.getBusyThreads() != base)
            {
                if (System.nanoTime() > end)
                    throw new TimeoutException();
                Thread.sleep(10);
            }

            // We are now asynchronously waiting!
            assertThat(COMPLETE.get(), is(false));

            // proceed with the completion
            delay.proceed();

            while(!COMPLETE.get())
            {
                if (System.nanoTime() > end)
                    throw new TimeoutException();
                Thread.sleep(10);
            }
        }
    }
}
