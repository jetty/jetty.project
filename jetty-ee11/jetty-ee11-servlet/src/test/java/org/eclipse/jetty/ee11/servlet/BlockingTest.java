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

package org.eclipse.jetty.ee11.servlet;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ExceptionUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class BlockingTest
{
    private Server server;
    private ServerConnector connector;
    private ServletContextHandler context;

    @BeforeEach
    public void setUp()
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        context = new ServletContextHandler("/ctx");
        server.setHandler(context);
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
        AtomicReference<Thread> threadRef = new AtomicReference<>();
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                Thread thread = new Thread(() ->
                {
                    try
                    {
                        int b = req.getInputStream().read();
                        if (b == '1')
                        {
                            started.countDown();
                            if (req.getInputStream().read() > Integer.MIN_VALUE)
                                throw new IllegalStateException();
                        }
                    }
                    catch (Throwable t)
                    {
                        readException.set(t);
                        stopped.countDown();
                    }
                });
                threadRef.set(thread);
                thread.start();

                try
                {
                    // wait for thread to start and read first byte
                    assertTrue(started.await(10, TimeUnit.SECONDS));
                    // give it time to block on second byte
                    await().atMost(5, TimeUnit.SECONDS).until(() ->
                    {
                        Thread.State state = thread.getState();
                        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
                    });
                }
                catch (Throwable e)
                {
                    throw new ServletException(e);
                }

                resp.setStatus(200);
                resp.setContentType("text/plain");
                resp.getOutputStream().print("OK\r\n");
            }
        };
        context.addServlet(servlet, "/*");
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
            boolean await = stopped.await(10, TimeUnit.SECONDS);
            if (!await)
            {
                StackTraceElement[] stackTrace = threadRef.get().getStackTrace();
                for (StackTraceElement stackTraceElement : stackTrace)
                {
                    System.err.println(stackTraceElement);
                }
            }
            assertTrue(await);
            assertThat(readException.get(), instanceOf(IOException.class));
        }
    }

    @Test
    public void testBlockingCloseWhileReading() throws Exception
    {
        AtomicReference<Thread> threadRef = new AtomicReference<>();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();

        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp)
            {
                try
                {
                    AsyncContext asyncContext = req.startAsync();
                    ServletOutputStream outputStream = resp.getOutputStream();
                    resp.setStatus(200);
                    resp.setContentType("text/plain");

                    Thread thread = new Thread(() ->
                    {
                        try
                        {
                            try
                            {
                                for (int i = 0; i < 5; i++)
                                {
                                    int b = req.getInputStream().read();
                                    assertThat(b, not(is(-1)));
                                }
                                outputStream.write("All read.".getBytes(StandardCharsets.UTF_8));
                            }
                            catch (IOException e)
                            {
                                throw new RuntimeException(e);
                            }

                            // this read should throw IOException as the client has closed the connection
                            assertThrows(IOException.class, () -> req.getInputStream().read());

                            try
                            {
                                outputStream.close();
                            }
                            catch (IOException e)
                            {
                                // can happen
                            }
                            finally
                            {
                                try
                                {
                                    asyncContext.complete();
                                }
                                catch (Exception e)
                                {
                                    // tolerated
                                }
                            }
                        }
                        catch (Throwable x)
                        {
                            threadFailure.set(x);
                        }
                    })
                    {
                        @Override
                        public String toString()
                        {
                            return super.toString() + " " + outputStream;
                        }
                    };
                    threadRef.set(thread);
                    thread.start();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }
        };
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.addServlet(servlet, "/*");

        server.setHandler(contextHandler);
        server.start();

        String request = "POST /ctx/path/info HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: test/data\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "10\r\n" +
            "01234";

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));

            // Wait for handler thread to be started and for it to have read all bytes of the request.
            await().pollInterval(1, TimeUnit.MICROSECONDS).atMost(5, TimeUnit.SECONDS).until(() ->
            {
                Thread thread = threadRef.get();
                return thread != null && (thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING);
            });
        }
        threadRef.get().join(5000);
        if (threadRef.get().isAlive())
        {
            System.err.println("Blocked handler thread: " + threadRef.get().toString());
            for (StackTraceElement stackTraceElement : threadRef.get().getStackTrace())
            {
                System.err.println("\tat " + stackTraceElement);
            }
            fail("handler thread should not be alive anymore");
        }
        assertThat("handler thread should not be alive anymore", threadRef.get().isAlive(), is(false));
        assertThat("handler thread failed: " + ExceptionUtil.toString(threadFailure.get()), threadFailure.get(), nullValue());
    }

    @Test
    public void testNormalCompleteThenBlockingRead() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> readException = new AtomicReference<>();
        AtomicReference<Thread> threadRef = new AtomicReference<>();
        HttpServlet handler = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                Thread thread = new Thread(() ->
                {
                    try
                    {
                        int b = req.getInputStream().read();
                        if (b == '1')
                        {
                            started.countDown();
                            assertTrue(completed.await(10, TimeUnit.SECONDS));
                            if (req.getInputStream().read() > Integer.MIN_VALUE)
                                throw new IllegalStateException();
                        }
                    }
                    catch (Throwable t)
                    {
                        readException.set(t);
                        stopped.countDown();
                    }
                });
                threadRef.set(thread);
                thread.start();

                try
                {
                    // wait for thread to start and read first byte
                    assertTrue(started.await(10, TimeUnit.SECONDS));
                    // give it time to block on second byte
                    await().atMost(5, TimeUnit.SECONDS).until(() ->
                    {
                        Thread.State state = thread.getState();
                        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
                    });
                }
                catch (Throwable e)
                {
                    throw new ServletException(e);
                }

                resp.setStatus(200);
                resp.setContentType("text/plain");
                resp.getOutputStream().print("OK\r\n");
            }
        };
        context.addServlet(handler, "/*");
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
            await().atMost(5, TimeUnit.SECONDS).until(() -> threadRef.get().getState() == Thread.State.TERMINATED);

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
        AtomicReference<Thread> threadRef = new AtomicReference<>();
        AtomicReference<Throwable> readException = new AtomicReference<>();
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getDispatcherType() != DispatcherType.ERROR)
                {
                    AsyncContext async = req.startAsync();
                    async.setTimeout(100);

                    Thread thread = new Thread(() ->
                    {
                        try
                        {
                            int b = req.getInputStream().read();
                            if (b == '1')
                            {
                                started.countDown();
                                assertTrue(completed.await(10, TimeUnit.SECONDS));
                                if (req.getInputStream().read() > Integer.MIN_VALUE)
                                    throw new IllegalStateException();
                            }
                        }
                        catch (Throwable t)
                        {
                            readException.set(t);
                            stopped.countDown();
                        }
                    });
                    threadRef.set(thread);
                    thread.start();

                    try
                    {
                        // wait for thread to start and read first byte
                        assertTrue(started.await(10, TimeUnit.SECONDS));
                        // give it time to block on second byte
                        await().atMost(5, TimeUnit.SECONDS).until(() ->
                        {
                            Thread.State state = thread.getState();
                            return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
                        });
                    }
                    catch (Throwable e)
                    {
                        throw new ServletException(e);
                    }
                }
            }
        };
        context.addServlet(servlet, "/*");
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
            await().atMost(5, TimeUnit.SECONDS).until(() -> threadRef.get().getState() == Thread.State.TERMINATED);

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
        AtomicReference<Thread> threadRef = new AtomicReference<>();
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getDispatcherType() != DispatcherType.ERROR)
                {
                    Thread thread = new Thread(() ->
                    {
                        try
                        {
                            int b = req.getInputStream().read();
                            if (b == '1')
                            {
                                started.countDown();
                                if (req.getInputStream().read() > Integer.MIN_VALUE)
                                    throw new IllegalStateException();
                            }
                        }
                        catch (Throwable t)
                        {
                            readException.set(t);
                            stopped.countDown();
                        }
                    });
                    threadRef.set(thread);
                    thread.start();

                    try
                    {
                        // wait for thread to start and read first byte
                        assertTrue(started.await(10, TimeUnit.SECONDS));
                        // give it time to block on second byte
                        await().atMost(5, TimeUnit.SECONDS).until(() ->
                        {
                            Thread.State state = thread.getState();
                            return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
                        });
                    }
                    catch (Throwable e)
                    {
                        throw new ServletException(e);
                    }

                    resp.sendError(499);
                }
            }
        };
        context.addServlet(servlet, "/*");
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
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));

            HttpTester.Response response = HttpTester.parseResponse(socket.getInputStream());
            assertThat(response, notNullValue());
            assertThat(response.getStatus(), is(499));

            // Async thread should have stopped
            boolean await = stopped.await(10, TimeUnit.SECONDS);
            if (!await)
            {
                StackTraceElement[] stackTrace = threadRef.get().getStackTrace();
                for (StackTraceElement stackTraceElement : stackTrace)
                {
                    System.err.println(stackTraceElement.toString());
                }
            }
            assertTrue(await);
            assertThat(readException.get(), instanceOf(IOException.class));
        }
    }

    @Test
    public void testBlockingWriteThenNormalComplete() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> readException = new AtomicReference<>();
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                resp.setStatus(200);
                resp.setContentType("text/plain");
                Thread thread = new Thread(() ->
                {
                    try
                    {
                        byte[] data = new byte[16 * 1024];
                        Arrays.fill(data, (byte)'X');
                        data[data.length - 2] = '\r';
                        data[data.length - 1] = '\n';
                        OutputStream out = resp.getOutputStream();
                        started.countDown();
                        while (true)
                            out.write(data);
                    }
                    catch (Throwable t)
                    {
                        readException.set(t);
                        stopped.countDown();
                    }
                });
                thread.start();

                try
                {
                    // wait for thread to start and read first byte
                    assertTrue(started.await(10, TimeUnit.SECONDS));
                    // give it time to block on write
                    await().atMost(5, TimeUnit.SECONDS).until(() ->
                    {
                        Thread.State state = thread.getState();
                        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
                    });
                }
                catch (Throwable e)
                {
                    throw new ServletException(e);
                }
            }
        };
        context.addServlet(servlet, "/*");
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
            assertThat(readException.get(), notNullValue());
        }
    }
}
