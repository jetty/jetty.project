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

package org.eclipse.jetty.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
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

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpParser;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpResponse;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

@RunWith(AdvancedRunner.class)
public class AsyncIOServletTest
{
    private Server server;
    private ServerConnector connector;
    private String path = "/path";
    private static final ThreadLocal<Throwable> scope = new ThreadLocal<>();

    public void startServer(HttpServlet servlet) throws Exception
    {
        startServer(servlet, 30000);
    }

    public void startServer(HttpServlet servlet, long idleTimeout) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        connector.setIdleTimeout(idleTimeout);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setDelayDispatchUntilContent(false);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/", false, false);
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder, path);

        context.addEventListener(new ContextHandler.ContextScopeListener()
        {
            @Override
            public void enterScope(Context context, Request request, Object reason)
            {
                if (scope.get() != null)
                {
                    System.err.println(Thread.currentThread() + " Already entered scope!!!");
                    scope.get().printStackTrace();
                    throw new IllegalStateException();
                }
                scope.set(new Throwable());
            }

            @Override
            public void exitScope(Context context, Request request)
            {
                if (scope.get() == null)
                    throw new IllegalStateException();
                scope.set(null);
            }
        });

        server.start();
    }

    private static void assertScope()
    {
        if (scope.get() == null)
            Assert.fail("Not in scope");
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
        if (scope.get() != null)
        {
            System.err.println("Still in scope after stop!");
            scope.get().printStackTrace();
            throw new IllegalStateException("Didn't leave scope");
        }
        scope.set(null);
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

    private void testAsyncReadThrows(final Throwable throwable) throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                assertScope();
                final AsyncContext asyncContext = request.startAsync(request, response);
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

        String data = "0123456789";
        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "Content-Length: " + data.length() + "\r\n" +
                "\r\n" +
                data;

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            client.setSoTimeout(5000);
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String line = in.readLine();
            assertThat(line, containsString("500 Server Error"));
            while (line.length() > 0)
            {
                line = in.readLine();
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAsyncReadIdleTimeout() throws Exception
    {
        final int status = 567;
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                assertScope();
                final AsyncContext asyncContext = request.startAsync(request, response);
                asyncContext.setTimeout(0);
                final ServletInputStream inputStream = request.getInputStream();
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
        }, 1000);

        String data1 = "0123456789";
        String data2 = "ABCDEF";
        // Only send the first chunk of data and then let it idle timeout.
        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "Content-Length: " + (data1.length() + data2.length()) + "\r\n" +
                "\r\n" +
                data1;

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            client.setSoTimeout(5000);
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            SimpleHttpParser parser = new SimpleHttpParser();
            SimpleHttpResponse response = parser.readResponse(new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8")));

            assertEquals(String.valueOf(status), response.getCode());

            // Make sure the connection was closed by the server.
            assertEquals(-1, client.getInputStream().read());
        }
    }

    @Test
    public void testOnErrorThrows() throws Exception
    {
        final AtomicInteger errors = new AtomicInteger();
        startServer(new HttpServlet()
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
                    public void onError(final Throwable t)
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

        String data = "0123456789";
        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "Content-Length: " + data.length() + "\r\n" +
                "\r\n" +
                data;

        try (Socket client = new Socket("localhost", connector.getLocalPort());
             StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            SimpleHttpParser parser = new SimpleHttpParser();
            SimpleHttpResponse response = parser.readResponse(new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8")));

            Assert.assertEquals("500", response.getCode());
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

    private void testAsyncWriteThrows(final Throwable throwable) throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                assertScope();
                final AsyncContext asyncContext = request.startAsync(request, response);
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

        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "\r\n";

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            SimpleHttpParser parser = new SimpleHttpParser();
            SimpleHttpResponse response = parser.readResponse(new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8")));

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            Assert.assertEquals("500", response.getCode());
        }
    }


    @Test
    public void testAsyncWriteClosed() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        String text = "Now is the winter of our discontent. How Now Brown Cow. The quick brown fox jumped over the lazy dog.\n";
        for (int i = 0; i < 10; i++)
            text = text + text;
        final byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                assertScope();
                response.flushBuffer();

                final AsyncContext async = request.startAsync();
                final ServletOutputStream out = response.getOutputStream();
                out.setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        assertScope();
                        while (out.isReady())
                        {
                            try
                            {
                                Thread.sleep(100);
                                out.write(data);
                            }
                            catch (IOException e)
                            {
                                throw e;
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        async.complete();
                        latch.countDown();
                    }
                });
            }
        });

        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "\r\n";

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String line = in.readLine();
            assertThat(line, containsString("200 OK"));
            while (line.length() > 0)
                line = in.readLine();
            line = in.readLine();
            assertThat(line, not(containsString(" ")));
            line = in.readLine();
            assertThat(line, containsString("discontent. How Now Brown Cow. The "));
        }

        if (!latch.await(5, TimeUnit.SECONDS))
            Assert.fail();
    }


    @Test
    public void testIsReadyAtEOF() throws Exception
    {
        String text = "TEST\n";
        final byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                assertScope();
                response.flushBuffer();

                final AsyncContext async = request.startAsync();
                final ServletInputStream in = request.getInputStream();
                final ServletOutputStream out = response.getOutputStream();

                in.setReadListener(new ReadListener()
                {
                    transient int _i = 0;
                    transient boolean _minusOne = false;
                    transient boolean _finished = false;

                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        t.printStackTrace();
                        async.complete();
                    }

                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        assertScope();
                        while (in.isReady() && !in.isFinished())
                        {
                            int b = in.read();
                            if (b == -1)
                                _minusOne = true;
                            else if (data[_i++] != b)
                                throw new IllegalStateException();
                        }

                        if (in.isFinished())
                            _finished = true;
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        assertScope();
                        out.write(String.format("i=%d eof=%b finished=%b", _i, _minusOne, _finished).getBytes(StandardCharsets.ISO_8859_1));
                        async.complete();
                    }
                });
            }
        });

        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();
            output.write(data);
            output.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String line = in.readLine();
            assertThat(line, containsString("200 OK"));
            while (line.length() > 0)
                line = in.readLine();
            line = in.readLine();
            assertThat(line, containsString("i=" + data.length + " eof=true finished=true"));
        }
    }


    @Test
    public void testOnAllDataRead() throws Exception
    {
        String text = "X";
        final byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                assertScope();
                response.flushBuffer();

                final AsyncContext async = request.startAsync();
                async.setTimeout(5000);
                final ServletInputStream in = request.getInputStream();
                final ServletOutputStream out = response.getOutputStream();

                in.setReadListener(new ReadListener()
                {
                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        t.printStackTrace();
                        async.complete();
                    }

                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        assertScope();
                        try
                        {
                            Thread.sleep(1000);
                            if (!in.isReady())
                                throw new IllegalStateException();
                            if (in.read() != 'X')
                                throw new IllegalStateException();
                            if (!in.isReady())
                                throw new IllegalStateException();
                            if (in.read() != -1)
                                throw new IllegalStateException();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        assertScope();
                        out.write("OK\n".getBytes(StandardCharsets.ISO_8859_1));
                        async.complete();
                    }
                });
            }
        });

        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            client.setSoTimeout(5000);
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();
            Thread.sleep(100);
            output.write(data);
            output.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String line = in.readLine();
            assertThat(line, containsString("200 OK"));
            while (line.length() > 0)
                line = in.readLine();
            line = in.readLine();
            assertThat(line, containsString("OK"));
        }
    }

    @Test
    public void testOtherThreadOnAllDataRead() throws Exception
    {
        String text = "X";
        final byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                assertScope();
                response.flushBuffer();

                final AsyncContext async = request.startAsync();
                async.setTimeout(500000);
                final ServletInputStream in = request.getInputStream();
                final ServletOutputStream out = response.getOutputStream();

                if (request.getDispatcherType() == DispatcherType.ERROR)
                    throw new IllegalStateException();

                in.setReadListener(new ReadListener()
                {
                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        t.printStackTrace();
                        async.complete();
                    }

                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        assertScope();
                        async.start(() ->
                        {
                            assertScope();
                            try
                            {
                                Thread.sleep(1000);
                                if (!in.isReady())
                                    throw new IllegalStateException();
                                if (in.read() != 'X')
                                    throw new IllegalStateException();
                                if (!in.isReady())
                                    throw new IllegalStateException();
                                if (in.read() != -1)
                                    throw new IllegalStateException();
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        });
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        out.write("OK\n".getBytes(StandardCharsets.ISO_8859_1));
                        async.complete();
                    }
                });
            }
        });

        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            client.setSoTimeout(500000);
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();
            Thread.sleep(100);
            output.write(data);
            output.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String line = in.readLine();
            assertThat(line, containsString("200 OK"));
            while (line.length() > 0)
                line = in.readLine();
            line = in.readLine();
            assertThat(line, containsString("OK"));
        }
    }


    @Test
    public void testCompleteBeforeOnAllDataRead() throws Exception
    {
        String text = "XYZ";
        final byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
        final AtomicBoolean allDataRead = new AtomicBoolean(false);

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                assertScope();
                response.flushBuffer();

                final AsyncContext async = request.startAsync();
                final ServletInputStream in = request.getInputStream();
                final ServletOutputStream out = response.getOutputStream();

                in.setReadListener(new ReadListener()
                {
                    @Override
                    public void onError(Throwable t)
                    {
                        assertScope();
                        t.printStackTrace();
                    }

                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        assertScope();
                        while (in.isReady())
                        {
                            int b = in.read();
                            if (b < 0)
                            {
                                out.write("OK\n".getBytes(StandardCharsets.ISO_8859_1));
                                async.complete();
                                return;
                            }
                        }
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        assertScope();
                        out.write("BAD!!!\n".getBytes(StandardCharsets.ISO_8859_1));
                        allDataRead.set(true);
                        throw new IllegalStateException();
                    }
                });
            }
        });

        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();
            Thread.sleep(100);
            output.write(data);
            output.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String line = in.readLine();
            assertThat(line, containsString("200 OK"));
            while (line.length() > 0)
            {
                line = in.readLine();
            }
            line = in.readLine();
            assertThat(line, containsString("OK"));
            Assert.assertFalse(allDataRead.get());
        }
    }


    @Test
    public void testEmptyAsyncRead() throws Exception
    {
        final AtomicBoolean oda = new AtomicBoolean();
        final CountDownLatch latch = new CountDownLatch(1);

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                assertScope();
                final AsyncContext asyncContext = request.startAsync(request, response);
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

        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            String response = IO.toString(client.getInputStream());
            assertThat(response, containsString(" 200 OK"));
            // wait for onAllDataRead BEFORE closing client
            latch.await();
        }

        // ODA not called at all!
        Assert.assertFalse(oda.get());
    }

    @Test
    public void testWriteFromOnDataAvailable() throws Exception
    {
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        CountDownLatch writeLatch = new CountDownLatch(1);
        startServer(new HttpServlet()
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

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();

            String request = "POST " + path + " HTTP/1.1\r\n" +
                    "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "10\r\n" +
                    content + "\r\n";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            assertTrue(writeLatch.await(5, TimeUnit.SECONDS));

            request = "" +
                    "0\r\n" +
                    "\r\n";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            HttpTester.Input input = HttpTester.from(client.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);

            assertThat(response.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
            assertThat(response.getContent(), Matchers.equalTo(content));
            assertThat(errors, Matchers.hasSize(0));
        }
    }
}
