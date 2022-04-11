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

package org.eclipse.jetty.ee9.handler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
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
    private static final int POLL = 10; // milliseconds
    private static final int WAIT = 10; // seconds
    private static final String SMALL = "Now is the time for all good men to come to the aid of the party. ";
    private static final String LARGE = SMALL + SMALL + SMALL + SMALL + SMALL;
    private static final int BUFFER_SIZE = SMALL.length() * 3 / 2;
    private static final BlockingQueue<PendingCallback> __queue = new BlockingArrayQueue<>();
    private static final AtomicBoolean __transportComplete = new AtomicBoolean();

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
                _pending.get(WAIT, TimeUnit.SECONDS);
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
        __transportComplete.set(false);

        initServer(new ServerConnector(_server, new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                getHttpConfiguration().setOutputBufferSize(BUFFER_SIZE);
                getHttpConfiguration().setOutputAggregationSize(BUFFER_SIZE);
                Connection connection = super.newConnection(connector, endPoint);
                if (connection instanceof AbstractConnection abstractConnection)
                {
                    abstractConnection.addEventListener(new Connection.Listener()
                    {
                        @Override
                        public void onClosed(Connection connection)
                        {
                            __transportComplete.compareAndSet(false, true);
                        }
                    });
                }
                return connection;
            }
        })
        {
            @Override
            protected SocketChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key)
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
            __queue.offer(delay);
        }
    }

    enum WriteStyle
    {
        ARRAY, BUFFER, BYTE, BYTE_THEN_ARRAY, PRINT
    }

    public static Stream<Arguments> asyncIOWriteTests()
    {
        List<Object[]> tests = new ArrayList<>();
        for (WriteStyle w : WriteStyle.values())
        {
            for (boolean contentLength : new Boolean[]{true, false})
            {
                for (boolean isReady : new Boolean[]{true, false})
                {
                    for (boolean flush : new Boolean[]{true, false})
                    {
                        for (boolean close : new Boolean[]{true, false})
                        {
                            for (String data : new String[]{SMALL, LARGE})
                            {
                                tests.add(new Object[]{new AsyncIOWriteHandler(w, contentLength, isReady, flush, close, data)});
                            }
                        }
                    }
                }
            }
        }
        return tests.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("asyncIOWriteTests")
    public void testAsyncIOWrite(AsyncIOWriteHandler handler) throws Exception
    {
        startServer(handler);

        int base = _threadPool.getBusyThreads();
        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            InputStream in = client.getInputStream();

            // write the request
            os.write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // wait for OWP to execute (proves we do not block in write APIs)
            boolean completeCalled = handler.waitForOWPExit();

            while (true)
            {
                // wait for threads to return to base level (proves we are really async)
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT);
                while (_threadPool.getBusyThreads() != base)
                {
                    if (System.nanoTime() > end)
                        throw new TimeoutException();
                    Thread.sleep(POLL);
                }

                if (completeCalled)
                    break;

                // We are now asynchronously waiting!
                assertThat(__transportComplete.get(), is(false));

                // If we are not complete, we must be waiting for one or more writes to complete
                while (true)
                {
                    PendingCallback delay = __queue.poll(POLL, TimeUnit.MILLISECONDS);
                    if (delay != null)
                    {
                        delay.proceed();
                        continue;
                    }
                    // No delay callback found, have we finished OWP again?
                    Boolean c = handler.pollForOWPExit();

                    if (c == null)
                        // No we haven't, so look for another delay callback
                        continue;

                    // We have a OWP result, so let's handle it.
                    completeCalled = c;
                    break;
                }
            }

            // Wait for full completion
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT);
            while (!__transportComplete.get())
            {
                if (System.nanoTime() > end)
                    throw new TimeoutException();

                // proceed with any delayCBs needed for completion
                PendingCallback delay = __queue.poll(POLL, TimeUnit.MILLISECONDS);
                if (delay != null)
                    delay.proceed();
            }

            // Check we got a response!
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertThat(response, Matchers.notNullValue());
            assertThat(response.getStatus(), is(200));
            String content = response.getContent();
            assertThat(content, containsString(handler.getExpectedMessage()));
        }
    }

    private static class AsyncIOWriteHandler extends AbstractHandler
    {
        final WriteStyle _write;
        final boolean _contentLength;
        final boolean _isReady;
        final boolean _flush;
        final boolean _close;
        final String _data;
        final Exchanger<Boolean> _ready = new Exchanger<>();
        int _toWrite;
        boolean _flushed;
        boolean _closed;

        AsyncIOWriteHandler(WriteStyle write, boolean contentLength, boolean isReady, boolean flush, boolean close, String data)
        {
            _write = write;
            _contentLength = contentLength;
            _isReady = isReady;
            _flush = flush;
            _close = close;
            _data = data;
            _toWrite = data.length();
        }

        public String getExpectedMessage()
        {
            return SMALL;
        }

        boolean waitForOWPExit()
        {
            try
            {
                return _ready.exchange(null);
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }

        Boolean pollForOWPExit()
        {
            try
            {
                return _ready.exchange(null, POLL, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
            catch (TimeoutException e)
            {
                return null;
            }
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            AsyncContext context = request.startAsync();
            ServletOutputStream out = response.getOutputStream();
            response.setContentType("text/plain");
            byte[] bytes = _data.getBytes(StandardCharsets.ISO_8859_1);
            if (_contentLength)
                response.setContentLength(bytes.length);

            out.setWriteListener(new WriteListener()
            {
                @Override
                public void onWritePossible() throws IOException
                {
                    try
                    {
                        if (out.isReady())
                        {
                            if (_toWrite > 0)
                            {
                                switch (_write)
                                {
                                    case ARRAY:
                                        _toWrite = 0;
                                        out.write(bytes, 0, bytes.length);
                                        break;

                                    case BUFFER:
                                        _toWrite = 0;
                                        ((HttpOutput)out).write(BufferUtil.toBuffer(bytes));
                                        break;

                                    case BYTE:
                                        for (int i = bytes.length - _toWrite; i < bytes.length; i++)
                                        {
                                            _toWrite--;
                                            out.write(bytes[i]);
                                            boolean ready = out.isReady();
                                            if (!ready)
                                            {
                                                _ready.exchange(Boolean.FALSE);
                                                return;
                                            }
                                        }
                                        break;

                                    case BYTE_THEN_ARRAY:
                                        _toWrite = 0;
                                        out.write(bytes[0]); // This should always aggregate
                                        assertThat(out.isReady(), is(true));
                                        out.write(bytes, 1, bytes.length - 1);
                                        break;

                                    case PRINT:
                                        _toWrite = 0;
                                        out.print(_data);
                                        break;
                                }
                            }

                            if (_flush && !_flushed)
                            {
                                boolean ready = out.isReady();
                                if (!ready)
                                {
                                    _ready.exchange(Boolean.FALSE);
                                    return;
                                }
                                _flushed = true;
                                out.flush();
                            }

                            if (_close && !_closed)
                            {
                                if (_isReady)
                                {
                                    boolean ready = out.isReady();
                                    if (!ready)
                                    {
                                        _ready.exchange(Boolean.FALSE);
                                        return;
                                    }
                                }
                                _closed = true;
                                out.close();
                            }

                            if (_isReady)
                            {
                                boolean ready = out.isReady();
                                if (!ready)
                                {
                                    _ready.exchange(Boolean.FALSE);
                                    return;
                                }
                            }
                            context.complete();
                            _ready.exchange(Boolean.TRUE);
                        }
                    }
                    catch (InterruptedException e)
                    {
                        throw new RuntimeException(e);
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
            return String.format("AWCH{w=%s,cl=%b,ir=%b,f=%b,c=%b,d=%d}", _write, _contentLength, _isReady, _flush, _close, _data.length());
        }
    }

    public static Stream<Arguments> blockingWriteTests()
    {
        List<Object[]> tests = new ArrayList<>();
        for (WriteStyle w : WriteStyle.values())
        {
            for (boolean contentLength : new Boolean[]{true, false})
            {
                for (boolean flush : new Boolean[]{true, false})
                {
                    for (boolean close : new Boolean[]{true, false})
                    {
                        for (String data : new String[]{SMALL, LARGE})
                        {
                            tests.add(new Object[]{new BlockingWriteHandler(w, contentLength, flush, close, data)});
                        }
                    }
                }
            }
        }
        return tests.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("blockingWriteTests")
    public void testBlockingWrite(BlockingWriteHandler handler) throws Exception
    {
        startServer(handler);

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            InputStream in = client.getInputStream();

            // write the request
            os.write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            handler.wait4handle();

            // Wait for full completion
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT);
            while (!__transportComplete.get())
            {
                if (System.nanoTime() > end)
                    throw new TimeoutException();

                // proceed with any delayCBs needed for completion
                try
                {
                    PendingCallback delay = __queue.poll(POLL, TimeUnit.MILLISECONDS);
                    if (delay != null)
                        delay.proceed();
                }
                catch (Exception e)
                {
                    // ignored
                }
            }

            // Check we got a response!
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertThat(response, Matchers.notNullValue());
            assertThat(response.getStatus(), is(200));
            String content = response.getContent();
            assertThat(content, containsString(handler.getExpectedMessage()));
        }
    }

    private static class BlockingWriteHandler extends AbstractHandler
    {
        final WriteStyle _write;
        final boolean _contentLength;
        final boolean _flush;
        final boolean _close;
        final String _data;
        final CountDownLatch _wait = new CountDownLatch(1);

        BlockingWriteHandler(WriteStyle write, boolean contentLength, boolean flush, boolean close, String data)
        {
            _write = write;
            _contentLength = contentLength;
            _flush = flush;
            _close = close;
            _data = data;
        }

        public String getExpectedMessage()
        {
            return SMALL;
        }

        public void wait4handle()
        {
            try
            {
                Assertions.assertTrue(_wait.await(WAIT, TimeUnit.SECONDS));
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            AsyncContext context = request.startAsync();
            ServletOutputStream out = response.getOutputStream();

            context.start(() ->
            {
                try
                {
                    _wait.countDown();

                    response.setContentType("text/plain");
                    byte[] bytes = _data.getBytes(StandardCharsets.ISO_8859_1);
                    if (_contentLength)
                        response.setContentLength(bytes.length);

                    switch (_write)
                    {
                        case ARRAY:
                            out.write(bytes, 0, bytes.length);
                            break;

                        case BUFFER:
                            ((HttpOutput)out).write(BufferUtil.toBuffer(bytes));
                            break;

                        case BYTE:
                            for (byte b : bytes)
                            {
                                out.write(b);
                            }
                            break;

                        case BYTE_THEN_ARRAY:
                            out.write(bytes[0]); // This should always aggregate
                            out.write(bytes, 1, bytes.length - 1);
                            break;

                        case PRINT:
                            out.print(_data);
                            break;
                    }

                    if (_flush)
                        out.flush();

                    if (_close)
                        out.close();

                    context.complete();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public String toString()
        {
            return String.format("BWCH{w=%s,cl=%b,f=%b,c=%b,d=%d}", _write, _contentLength, _flush, _close, _data.length());
        }
    }

    public static Stream<Arguments> sendContentTests()
    {
        List<Object[]> tests = new ArrayList<>();
        for (ContentStyle style : ContentStyle.values())
        {
            for (String data : new String[]{SMALL, LARGE})
            {
                tests.add(new Object[]{new SendContentHandler(style, data)});
            }
        }
        return tests.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("sendContentTests")
    public void testSendContent(SendContentHandler handler) throws Exception
    {
        startServer(handler);

        int base = _threadPool.getBusyThreads();
        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            InputStream in = client.getInputStream();

            // write the request
            os.write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            handler.wait4handle();

            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT);
            while (_threadPool.getBusyThreads() != base)
            {
                if (System.nanoTime() > end)
                    throw new TimeoutException();
                Thread.sleep(POLL);
            }

            // Wait for full completion
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT);
            while (!__transportComplete.get())
            {
                if (System.nanoTime() > end)
                    throw new TimeoutException();

                // proceed with any delayCBs needed for completion
                try
                {
                    PendingCallback delay = __queue.poll(POLL, TimeUnit.MILLISECONDS);
                    if (delay != null)
                        delay.proceed();
                }
                catch (Exception e)
                {
                    // ignored
                }
            }

            // Check we got a response!
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertThat(response, Matchers.notNullValue());
            assertThat(response.getStatus(), is(200));
            String content = response.getContent();
            assertThat(content, containsString(handler.getExpectedMessage()));
        }
    }

    enum ContentStyle
    {
        BUFFER, STREAM
        // TODO more types needed here
    }

    private static class SendContentHandler extends AbstractHandler
    {
        final ContentStyle _style;
        final String _data;
        final CountDownLatch _wait = new CountDownLatch(1);

        SendContentHandler(ContentStyle style, String data)
        {
            _style = style;
            _data = data;
        }

        public String getExpectedMessage()
        {
            return SMALL;
        }

        public void wait4handle()
        {
            try
            {
                Assertions.assertTrue(_wait.await(WAIT, TimeUnit.SECONDS));
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            AsyncContext context = request.startAsync();
            HttpOutput out = (HttpOutput)response.getOutputStream();

            response.setContentType("text/plain");
            byte[] bytes = _data.getBytes(StandardCharsets.ISO_8859_1);

            switch (_style)
            {
                case BUFFER:
                    out.sendContent(BufferUtil.toBuffer(bytes), Callback.from(context::complete));
                    break;

                case STREAM:
                    out.sendContent(new ByteArrayInputStream(bytes), Callback.from(context::complete));
                    break;
            }

            _wait.countDown();
        }

        @Override
        public String toString()
        {
            return String.format("SCCH{w=%s,d=%d}", _style, _data.length());
        }
    }
}
