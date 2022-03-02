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

package org.eclipse.jetty.server.handler;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DelayedHandlerTest
{
    private Server _server;
    private ServerConnector _connector;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testNotDelayed() throws Exception
    {
        DelayedHandler delayedHandler = new DelayedHandler()
        {
            @Override
            protected Request.Processor delayed(Request request, Request.Processor processor)
            {
                return processor;
            }
        };

        _server.setHandler(delayedHandler);
        delayedHandler.setHandler(new HelloHandler());
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                GET / HTTP/1.1\r
                Host: localhost\r
                \r
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("Hello"));
        }
    }

    @Test
    public void testDelayed() throws Exception
    {
        Exchanger<Runnable> handleEx = new Exchanger<>();
        DelayedHandler delayedHandler = new DelayedHandler()
        {
            @Override
            protected Request.Processor delayed(Request request, Request.Processor processor)
            {
                return (ignored, response, callback) -> handleEx.exchange(() ->
                {
                    try
                    {
                        processor.process(request, response, callback);
                    }
                    catch (Throwable e)
                    {
                        Response.writeError(request, response, callback, e);
                    }
                });
            }
        };

        _server.setHandler(delayedHandler);
        CountDownLatch processing = new CountDownLatch(1);
        delayedHandler.setHandler(new HelloHandler()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                processing.countDown();
                super.process(request, response, callback);
            }
        });
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                GET / HTTP/1.1\r
                Host: localhost\r
                \r
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            Runnable handle = handleEx.exchange(null, 10, TimeUnit.SECONDS);
            assertNotNull(handle);
            assertFalse(processing.await(250, TimeUnit.MILLISECONDS));

            handle.run();

            assertTrue(processing.await(10, TimeUnit.SECONDS));

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("Hello"));
        }
    }

    @Test
    public void testOnContent() throws Exception
    {
        DelayedHandler delayedHandler = new DelayedHandler.UntilContent();

        _server.setHandler(delayedHandler);
        CountDownLatch processing = new CountDownLatch(1);
        delayedHandler.setHandler(new HelloHandler()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                processing.countDown();
                super.process(request, response, callback);
            }
        });
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                POST / HTTP/1.1\r
                Host: localhost\r
                Content-Length: 10\r
                \r
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            assertFalse(processing.await(250, TimeUnit.MILLISECONDS));

            output.write("01234567\r\n".getBytes(StandardCharsets.UTF_8));
            output.flush();

            assertTrue(processing.await(10, TimeUnit.SECONDS));

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("Hello"));
        }
    }

    @Test
    public void testQualityOfService() throws Exception
    {
        // TODO this test is flakey
        final int QOS = 3;
        final int EXTRA = 2;

        DelayedHandler delayedHandler = new DelayedHandler.QualityOfService(QOS);
        _server.setHandler(delayedHandler);

        AtomicInteger processing = new AtomicInteger();
        Semaphore semaphore = new Semaphore(0);
        delayedHandler.setHandler(new HelloHandler()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                processing.incrementAndGet();
                semaphore.acquire();
                super.process(request, response, callback);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.BLOCKING;
            }
        });
        _server.start();

        Socket[] socket = new Socket[QOS + EXTRA];
        for (int i = 0; i < socket.length; i++)
        {
            socket[i] = new Socket("localhost", _connector.getLocalPort());
            String request = "GET /p" + i + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
            OutputStream output = socket[i].getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        waitFor(processing, QOS);
        assertThat(processing.get(), is(QOS));

        for (int i = 0; i < socket.length; i++)
        {
            semaphore.release();
            int count = i + 1;

            waitFor(processing, QOS + Math.min(EXTRA, count));
            assertThat(processing.get(), is(QOS + Math.min(EXTRA, count)));
        }

        for (Socket value : socket)
        {
            HttpTester.Input input = HttpTester.from(value.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("Hello"));
        }
    }

    @Test
    public void testDelayed404() throws Exception
    {
        DelayedHandler delayedHandler = new DelayedHandler()
        {
            @Override
            protected Request.Processor delayed(Request request, Request.Processor processor)
            {
                return (ignored, response, callback) -> request.getContext().execute(() ->
                {
                    try
                    {
                        processor.process(request, response, callback);
                    }
                    catch (Throwable t)
                    {
                        Response.writeError(request, response, callback, t);
                    }
                });
            }

        };

        _server.setHandler(delayedHandler);
        delayedHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public Request.Processor handle(Request request)
            {
                return null;
            }
        });

        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                GET / HTTP/1.1\r
                Host: localhost\r
                \r
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("<tr><th>MESSAGE:</th><td>Not Found</td></tr>"));
        }
    }

    @Test
    public void testDelayedDefault() throws Exception
    {
        DelayedHandler delayedHandler = new DelayedHandler()
        {
            @Override
            protected Request.Processor delayed(Request request, Request.Processor processor)
            {
                return (ignored, response, callback) -> request.getContext().execute(() ->
                {
                    try
                    {
                        processor.process(request, response, callback);
                    }
                    catch (Throwable t)
                    {
                        Response.writeError(request, response, callback, t);
                    }
                });
            }
        };

        delayedHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public Request.Processor handle(Request request)
            {
                return null;
            }
        });

        Handler.Collection handlers = new Handler.Collection();
        handlers.setHandlers(delayedHandler, new DefaultHandler());
        _server.setHandler(handlers);

        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                GET / HTTP/1.1\r
                Host: localhost\r
                \r
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("<p>No context on this server matched or handled this request.</p>"));
        }
    }

    private void waitFor(AtomicInteger test, int value) throws TimeoutException
    {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (test.get() < value)
        {
            if (System.nanoTime() > end)
                throw new TimeoutException();
            Thread.onSpinWait();
        }
    }
}
