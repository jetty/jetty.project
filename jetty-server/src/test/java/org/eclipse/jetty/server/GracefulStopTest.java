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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GracefulStopTest
{
    static byte[] POST_12345 = ("POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: plain/text\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n" +
                "12345").getBytes(StandardCharsets.ISO_8859_1);

    static byte[] POST_A_12345 = ("POST /a/ HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: plain/text\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n" +
                "12345").getBytes(StandardCharsets.ISO_8859_1);

    static byte[] POST_B_12345 = ("POST /b/ HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: plain/text\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n" +
                "12345").getBytes(StandardCharsets.ISO_8859_1);

    static byte[] POST_12345_C = ("POST /?commit=true HTTP/1.1\r\n" +
        "Host: localhost\r\n" +
        "Content-Type: plain/text\r\n" +
        "Content-Length: 10\r\n" +
        "\r\n" +
        "12345").getBytes(StandardCharsets.ISO_8859_1);

    static byte[] POST_A_12345_C = ("POST /a/?commit=true HTTP/1.1\r\n" +
        "Host: localhost\r\n" +
        "Content-Type: plain/text\r\n" +
        "Content-Length: 10\r\n" +
        "\r\n" +
        "12345").getBytes(StandardCharsets.ISO_8859_1);

    static byte[] POST_B_12345_C = ("POST /b/?commit=true HTTP/1.1\r\n" +
        "Host: localhost\r\n" +
        "Content-Type: plain/text\r\n" +
        "Content-Length: 10\r\n" +
        "\r\n" +
        "12345").getBytes(StandardCharsets.ISO_8859_1);

    static byte[] BODY_67890 = "67890".getBytes(StandardCharsets.ISO_8859_1);

    Server server = new Server();
    ServerConnector connector = new ServerConnector(server);
    HandlerList handlers = new HandlerList();
    ContextHandlerCollection contexts = new ContextHandlerCollection();
    ContextHandler contextA = new ContextHandler(contexts, "/a");
    TestHandler handlerA = new TestHandler();
    ContextHandler contextB = new ContextHandler(contexts, "/b");
    StatisticsHandler statsB = new StatisticsHandler();
    TestHandler handlerB = new TestHandler();
    TestHandler handler = new TestHandler();

    @BeforeEach
    public void beforeEach() throws Exception
    {
        connector.setIdleTimeout(10000);
        connector.setShutdownIdleTimeout(1000);
        connector.setPort(0);
        server.addConnector(connector);

        server.setHandler(handlers);
        handlers.addHandler(contexts);
        handlers.addHandler(handler);

        contextA.setHandler(handlerA);

        contextB.setHandler(statsB);
        statsB.setHandler(handlerB);

        server.setStopTimeout(10000);

        server.start();
    }

    Socket newClientBusy(byte[] post, TestHandler handler) throws Exception
    {
        handler.latch = new CountDownLatch(1);
        final int port = connector.getLocalPort();
        Socket client = new Socket("127.0.0.1", port);
        client.getOutputStream().write(post);
        client.getOutputStream().flush();
        assertTrue(handler.latch.await(5, TimeUnit.SECONDS));
        return client;
    }

    Socket newClientIdle(byte[] post, TestHandler handler) throws Exception
    {
        handler.latch = new CountDownLatch(1);
        final int port = connector.getLocalPort();
        Socket client = new Socket("127.0.0.1", port);
        client.getOutputStream().write(concat(post, BODY_67890));
        client.getOutputStream().flush();
        assertTrue(handler.latch.await(5, TimeUnit.SECONDS));

        HttpTester.Response response = HttpTester.parseResponse(client.getInputStream());
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("read [10/10]"));
        assertThat(response.get(HttpHeader.CONNECTION), nullValue());

        return client;
    }

    void assertAvailable(Socket client, byte[] post, TestHandler handler) throws Exception
    {
        handler.latch = new CountDownLatch(1);
        client.getOutputStream().write(concat(post, BODY_67890));
        client.getOutputStream().flush();
        assertTrue(handler.latch.await(5, TimeUnit.SECONDS));

        HttpTester.Response response = HttpTester.parseResponse(client.getInputStream());
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("read [10/10]"));
        assertThat(response.get(HttpHeader.CONNECTION), nullValue());
    }

    Future<Integer> backgroundUnavailable(Socket client, byte[] post, ContextHandler context, TestHandler handler) throws Exception
    {
        FuturePromise<Integer> future = new FuturePromise<>();
        long start = System.nanoTime();
        new Thread(() ->
        {
            try
            {
                while (context.isAvailable())
                {
                    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), lessThan(5000L));
                    Thread.sleep(100);
                }

                client.getOutputStream().write(concat(post, BODY_67890));
                client.getOutputStream().flush();
                HttpTester.Response response = HttpTester.parseResponse(client.getInputStream());

                future.succeeded(response.getStatus());
            }
            catch (Exception e)
            {
                future.failed(e);
            }
        }).start();

        return future;
    }

    void assertQuickStop() throws Exception
    {
        long start = System.nanoTime();
        server.stop();
        long stop = System.nanoTime();
        long duration = TimeUnit.NANOSECONDS.toMillis(stop - start);
        assertThat(duration, lessThan(2000L));
    }

    void assertGracefulStop(LifeCycle lifecycle) throws Exception
    {
        long start = System.nanoTime();
        lifecycle.stop();
        long stop = System.nanoTime();
        long duration = TimeUnit.NANOSECONDS.toMillis(stop - start);
        assertThat(duration, greaterThan(50L));
        assertThat(duration, lessThan(5000L));
    }

    void assertResponse(Socket client, boolean close) throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(client.getInputStream());
        assertThat(response.getStatus(), is(200));
        if (close)
            assertThat(response.get(HttpHeader.CONNECTION), is("close"));
        else
            assertThat(response.get(HttpHeader.CONNECTION), nullValue());
        assertThat(response.getContent(), is("read [10/10]"));
    }

    void assert500Response(Socket client) throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(client.getInputStream());
        if (response != null)
        {
            assertThat(response.getStatus(), is(500));
            assertThat(response.get(HttpHeader.CONNECTION), is("close"));
        }
    }

    void assertQuickClose(Socket client) throws Exception
    {
        long start = System.nanoTime();
        assertThat(client.getInputStream().read(), is(-1));
        long stop = System.nanoTime();
        long duration = TimeUnit.NANOSECONDS.toMillis(stop - start);
        assertThat(duration, lessThan(2000L));
    }

    void assertHandled(TestHandler handler, boolean error)
    {
        assertThat(handler.handling.get(), is(false));
        if (error)
            assertThat(handler.thrown.get(), Matchers.notNullValue());
        else
            assertThat(handler.thrown.get(), Matchers.nullValue());
    }

    void backgroundComplete(Socket client, TestHandler handler) throws Exception
    {
        long start = System.nanoTime();
        new Thread(() ->
        {
            try
            {
                handler.latch.await();
                long now = System.nanoTime();
                Thread.sleep(100 - TimeUnit.NANOSECONDS.toMillis(now - start));
                client.getOutputStream().write(BODY_67890);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }).start();
    }

    private byte[] concat(byte[] bytes1, byte[] bytes2)
    {
        byte[] bytes = Arrays.copyOf(bytes1, bytes1.length + bytes2.length);
        System.arraycopy(bytes2, 0, bytes, bytes1.length, bytes2.length);
        return bytes;
    }

    @Test
    public void testNotGraceful() throws Exception
    {
        server.setStopTimeout(0);

        server.start();
        Socket client0 = newClientBusy(POST_12345, handler);
        Socket client1 = newClientIdle(POST_12345, handler);

        assertQuickStop();
        assertQuickClose(client0);
        assertQuickClose(client1);
        assertHandled(handler, true);
    }

    @Test
    public void testGracefulConnection() throws Exception
    {
        Socket client0 = newClientBusy(POST_12345, handler);
        Socket client1 = newClientBusy(POST_12345_C, handler);
        Socket client2 = newClientIdle(POST_12345, handler);

        backgroundComplete(client0, handler);
        backgroundComplete(client1, handler);

        assertGracefulStop(server);

        assertResponse(client0, true);
        assertResponse(client1, false);
        assertQuickClose(client0);
        assertQuickClose(client1);
        assertQuickClose(client2);
        assertHandled(handler, false);
    }

    @Test
    public void testGracefulConnectionNotComplete() throws Exception
    {
        server.setStopTimeout(3000L);
        Socket client0 = newClientBusy(POST_12345, handler);
        Socket client1 = newClientBusy(POST_12345_C, handler);
        Socket client2 = newClientIdle(POST_12345, handler);

        assertGracefulStop(server);

        assert500Response(client0);
        assert500Response(client1);
        assertQuickClose(client0);
        assertQuickClose(client1);
        assertQuickClose(client2);
        assertHandled(handler, true);
    }

    @Test
    public void testGracefulWithContext() throws Exception
    {
        Socket client0 = newClientBusy(POST_A_12345, handlerA);
        Socket client1 = newClientBusy(POST_A_12345_C, handlerA);
        Socket client2 = newClientIdle(POST_A_12345, handlerA);

        backgroundComplete(client0, handlerA);
        backgroundComplete(client1, handlerA);
        Future<Integer> status2 = backgroundUnavailable(client2, POST_A_12345, contextA, handlerA);

        assertGracefulStop(server);

        assertResponse(client0, true);
        assertResponse(client1, false);
        assertThat(status2.get(), is(503));

        assertQuickClose(client0);
        assertQuickClose(client1);
        assertQuickClose(client2);
        assertHandled(handlerA, false);
    }

    @Test
    public void testGracefulContext() throws Exception
    {
        Socket client0 = newClientBusy(POST_B_12345, handlerB);
        Socket client1 = newClientBusy(POST_B_12345_C, handlerB);
        Socket client2 = newClientIdle(POST_B_12345, handlerB);

        backgroundComplete(client0, handlerB);
        backgroundComplete(client1, handlerB);
        Future<Integer> status2 = backgroundUnavailable(client2, POST_B_12345, contextB, handlerB);

        Graceful.shutdown(contextB).orTimeout(10, TimeUnit.SECONDS).get();

        assertResponse(client0, false);
        assertResponse(client1, false);
        assertThat(status2.get(), is(503));

        assertAvailable(client0, POST_A_12345, handlerA);
        assertAvailable(client1, POST_A_12345_C, handlerA);
        assertAvailable(client2, POST_A_12345, handlerA);

        assertHandled(handlerA, false);
        assertHandled(handlerB, false);
    }

    static class TestHandler extends AbstractHandler
    {
        final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
        final AtomicBoolean handling = new AtomicBoolean(false);
        volatile CountDownLatch latch;

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException
        {
            // Log.getRootLogger().info("Handle {} / {} ? {}", request.getContextPath(), request.getPathInfo(), request.getQueryString());
            handling.set(true);
            baseRequest.setHandled(true);
            response.setStatus(200);
            if ("true".equals(request.getParameter("commit")))
                response.flushBuffer();
            CountDownLatch l = latch;
            if (l != null)
                l.countDown();
            int c = 0;
            try
            {
                int contentLength = request.getContentLength();
                if (contentLength > 0)
                {
                    InputStream in = request.getInputStream();
                    while (in.read() >= 0)
                    {
                        c++;
                    }
                }

                response.getWriter().printf("read [%d/%d]", c, contentLength);
            }
            catch (Throwable th)
            {
                thrown.set(th);
                throw th;
            }
            finally
            {
                handling.set(false);
            }
        }
    }
}
