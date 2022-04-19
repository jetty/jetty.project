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

package org.eclipse.jetty.ee9.nested;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled // TODO
public class BlockingTest
{
    private Server server;
    private ServerConnector connector;
    private ContextHandler context;

    @BeforeEach
    public void setUp()
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        context = new ContextHandler(server, "/ctx");
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        server.stop();
    }

    @Test
    public void testBlockingReadThenNormalComplete() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> readException = new AtomicReference<>();
        AbstractHandler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                new Thread(() ->
                {
                    try
                    {
                        int b = baseRequest.getHttpInput().read();
                        if (b == '1')
                        {
                            started.countDown();
                            if (baseRequest.getHttpInput().read() > Integer.MIN_VALUE)
                                throw new IllegalStateException();
                        }
                    }
                    catch (Throwable t)
                    {
                        readException.set(t);
                        stopped.countDown();
                    }
                }).start();

                try
                {
                    // wait for thread to start and read first byte
                    started.await(10, TimeUnit.SECONDS);
                    // give it time to block on second byte
                    Thread.sleep(1000);
                }
                catch (Throwable e)
                {
                    throw new ServletException(e);
                }

                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().print("OK\r\n");
            }
        };
        context.setHandler(handler);
        server.start();

        StringBuilder request = new StringBuilder();
        request.append("POST /ctx/path/info HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("Content-Type: test/data\r\n")
            .append("Content-Length: 2\r\n")
            .append("\r\n")
            .append("1");

        int port = connector.getLocalPort();
        try (Socket socket = new Socket("localhost", port))
        {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));

            HttpTester.Response response = HttpTester.parseResponse(socket.getInputStream());
            assertThat(response, notNullValue());
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), containsString("OK"));

            // Async thread should have stopped
            assertTrue(stopped.await(10, TimeUnit.SECONDS));
            assertThat(readException.get(), instanceOf(IOException.class));
        }
    }

    @Test
    public void testBlockingReadAndBlockingWriteGzipped() throws Exception
    {
        AtomicReference<Thread> threadRef = new AtomicReference<>();
        CyclicBarrier barrier = new CyclicBarrier(2);

        AbstractHandler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    final AsyncContext asyncContext = baseRequest.startAsync();
                    final ServletOutputStream outputStream = response.getOutputStream();
                    final Thread thread = new Thread(() ->
                    {
                        try
                        {
                            for (int i = 0; i < 5; i++)
                            {
                                int b = baseRequest.getHttpInput().read();
                                assertThat(b, not(is(-1)));
                            }
                            outputStream.write("All read.".getBytes(StandardCharsets.UTF_8));
                            barrier.await(); // notify that all bytes were read
                            baseRequest.getHttpInput().read(); // this read should throw IOException as the client has closed the connection
                            throw new AssertionError("should have thrown IOException");
                        }
                        catch (Exception e)
                        {
                            //throw new RuntimeException(e);
                        }
                        finally
                        {
                            try
                            {
                                outputStream.close();
                            }
                            catch (Exception e2)
                            {
                                //e2.printStackTrace();
                            }
                            asyncContext.complete();
                        }
                    });
                    threadRef.set(thread);
                    thread.start();
                    barrier.await(); // notify that handler thread has started

                    response.setStatus(200);
                    response.setContentType("text/plain");
                    response.getOutputStream().print("OK\r\n");
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }
        };
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setHandler(handler);

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setMinGzipSize(1);
        gzipHandler.setHandler(contextHandler);
        server.setHandler(gzipHandler);
        server.start();

        StringBuilder request = new StringBuilder();
        // partial chunked request
        request.append("POST /ctx/path/info HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("Accept-Encoding: gzip, *\r\n")
            .append("Content-Type: test/data\r\n")
            .append("Transfer-Encoding: chunked\r\n")
            .append("\r\n")
            .append("10\r\n")
            .append("01234")
        ;

        int port = connector.getLocalPort();
        try (Socket socket = new Socket("localhost", port))
        {
            socket.setSoLinger(true, 0); // send TCP RST upon close instead of FIN
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            barrier.await(); // wait for handler thread to be started
            barrier.await(); // wait for all bytes of the request to be read
        }
        threadRef.get().join(5000);
        assertThat("handler thread should not be alive anymore", threadRef.get().isAlive(), is(false));
    }

    @Test
    public void testNormalCompleteThenBlockingRead() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> readException = new AtomicReference<>();
        AbstractHandler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                new Thread(() ->
                {
                    try
                    {
                        int b = baseRequest.getHttpInput().read();
                        if (b == '1')
                        {
                            started.countDown();
                            completed.await(10, TimeUnit.SECONDS);
                            Thread.sleep(500);
                            if (baseRequest.getHttpInput().read() > Integer.MIN_VALUE)
                                throw new IllegalStateException();
                        }
                    }
                    catch (Throwable t)
                    {
                        readException.set(t);
                        stopped.countDown();
                    }
                }).start();

                try
                {
                    // wait for thread to start and read first byte
                    started.await(10, TimeUnit.SECONDS);
                    // give it time to block on second byte
                    Thread.sleep(1000);
                }
                catch (Throwable e)
                {
                    throw new ServletException(e);
                }

                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().print("OK\r\n");
            }
        };
        context.setHandler(handler);
        server.start();

        StringBuilder request = new StringBuilder();
        request.append("POST /ctx/path/info HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("Content-Type: test/data\r\n")
            .append("Content-Length: 2\r\n")
            .append("\r\n")
            .append("1");

        int port = connector.getLocalPort();
        try (Socket socket = new Socket("localhost", port))
        {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));

            HttpTester.Response response = HttpTester.parseResponse(socket.getInputStream());
            assertThat(response, notNullValue());
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), containsString("OK"));

            completed.countDown();
            Thread.sleep(1000);

            // Async thread should have stopped
            assertTrue(stopped.await(10, TimeUnit.SECONDS));
            assertThat(readException.get(), instanceOf(IOException.class));
        }
    }

    @Test
    public void testStartAsyncThenBlockingReadThenTimeout() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> readException = new AtomicReference<>();
        AbstractHandler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException
            {
                baseRequest.setHandled(true);
                if (baseRequest.getDispatcherType() != DispatcherType.ERROR)
                {
                    AsyncContext async = request.startAsync();
                    async.setTimeout(100);

                    new Thread(() ->
                    {
                        try
                        {
                            int b = baseRequest.getHttpInput().read();
                            if (b == '1')
                            {
                                started.countDown();
                                completed.await(10, TimeUnit.SECONDS);
                                Thread.sleep(500);
                                if (baseRequest.getHttpInput().read() > Integer.MIN_VALUE)
                                    throw new IllegalStateException();
                            }
                        }
                        catch (Throwable t)
                        {
                            readException.set(t);
                            stopped.countDown();
                        }
                    }).start();

                    try
                    {
                        // wait for thread to start and read first byte
                        started.await(10, TimeUnit.SECONDS);
                        // give it time to block on second byte
                        Thread.sleep(1000);
                    }
                    catch (Throwable e)
                    {
                        throw new ServletException(e);
                    }
                }
            }
        };
        context.setHandler(handler);
        server.start();

        StringBuilder request = new StringBuilder();
        request.append("POST /ctx/path/info HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("Content-Type: test/data\r\n")
            .append("Content-Length: 2\r\n")
            .append("\r\n")
            .append("1");

        int port = connector.getLocalPort();
        try (Socket socket = new Socket("localhost", port))
        {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));

            HttpTester.Response response = HttpTester.parseResponse(socket.getInputStream());
            assertThat(response, notNullValue());
            assertThat(response.getStatus(), is(500));
            assertThat(response.getContent(), containsString("AsyncContext timeout"));

            completed.countDown();
            Thread.sleep(1000);

            // Async thread should have stopped
            assertTrue(stopped.await(10, TimeUnit.SECONDS));
            assertThat(readException.get(), instanceOf(IOException.class));
        }
    }

    @Test
    public void testBlockingReadThenSendError() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> readException = new AtomicReference<>();
        AbstractHandler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (baseRequest.getDispatcherType() != DispatcherType.ERROR)
                {
                    new Thread(() ->
                    {
                        try
                        {
                            int b = baseRequest.getHttpInput().read();
                            if (b == '1')
                            {
                                started.countDown();
                                if (baseRequest.getHttpInput().read() > Integer.MIN_VALUE)
                                    throw new IllegalStateException();
                            }
                        }
                        catch (Throwable t)
                        {
                            readException.set(t);
                            stopped.countDown();
                        }
                    }).start();

                    try
                    {
                        // wait for thread to start and read first byte
                        started.await(10, TimeUnit.SECONDS);
                        // give it time to block on second byte
                        Thread.sleep(1000);
                    }
                    catch (Throwable e)
                    {
                        throw new ServletException(e);
                    }

                    response.sendError(499);
                }
            }
        };
        context.setHandler(handler);
        server.start();

        StringBuilder request = new StringBuilder();
        request.append("POST /ctx/path/info HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("Content-Type: test/data\r\n")
            .append("Content-Length: 2\r\n")
            .append("\r\n")
            .append("1");

        int port = connector.getLocalPort();
        try (Socket socket = new Socket("localhost", port))
        {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));

            HttpTester.Response response = HttpTester.parseResponse(socket.getInputStream());
            assertThat(response, notNullValue());
            assertThat(response.getStatus(), is(499));

            // Async thread should have stopped
            assertTrue(stopped.await(10, TimeUnit.SECONDS));
            assertThat(readException.get(), instanceOf(IOException.class));
        }
    }

    @Test
    public void testBlockingWriteThenNormalComplete() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> readException = new AtomicReference<>();
        AbstractHandler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(200);
                response.setContentType("text/plain");
                new Thread(() ->
                {
                    try
                    {
                        byte[] data = new byte[16 * 1024];
                        Arrays.fill(data, (byte)'X');
                        data[data.length - 2] = '\r';
                        data[data.length - 1] = '\n';
                        OutputStream out = response.getOutputStream();
                        started.countDown();
                        while (true)
                            out.write(data);
                    }
                    catch (Throwable t)
                    {
                        readException.set(t);
                        stopped.countDown();
                    }
                }).start();

                try
                {
                    // wait for thread to start and read first byte
                    started.await(10, TimeUnit.SECONDS);
                    // give it time to block on write
                    Thread.sleep(1000);
                }
                catch (Throwable e)
                {
                    throw new ServletException(e);
                }
            }
        };
        context.setHandler(handler);
        server.start();

        StringBuilder request = new StringBuilder();
        request.append("GET /ctx/path/info HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("\r\n");

        int port = connector.getLocalPort();
        try (Socket socket = new Socket("localhost", port))
        {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));

            // Read the header
            List<String> header = new ArrayList<>();
            while (true)
            {
                String line = in.readLine();
                if (line.length() == 0)
                    break;
                header.add(line);
            }
            assertThat(header.get(0), containsString("200 OK"));

            // read one line of content
            String content = in.readLine();
            assertThat(content, is("4000"));
            content = in.readLine();
            assertThat(content, startsWith("XXXXXXXX"));

            // check that writing thread is stopped by end of request handling
            assertTrue(stopped.await(10, TimeUnit.SECONDS));

            // read until last line
            String last = null;
            while (true)
            {
                String line = in.readLine();
                if (line == null)
                    break;

                last = line;
            }

            // last line is not empty chunk, ie abnormal completion
            assertThat(last, startsWith("XXXXX"));
        }
    }
}
