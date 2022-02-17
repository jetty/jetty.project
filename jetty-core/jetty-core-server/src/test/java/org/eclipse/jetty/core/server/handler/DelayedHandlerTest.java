//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.core.server.handler;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.core.server.Handler;
import org.eclipse.jetty.core.server.Request;
import org.eclipse.jetty.core.server.Response;
import org.eclipse.jetty.core.server.Server;
import org.eclipse.jetty.core.server.ServerConnector;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
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

        _server.start();
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
            protected Response accept(Request request)
            {
                return null;
            }

            @Override
            protected void schedule(Request request, Runnable handle)
            {
            }
        };

        _server.setHandler(delayedHandler);
        delayedHandler.setHandler(new HelloHandler());
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
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
    public void testZeroDelayed() throws Exception
    {
        DelayedHandler delayedHandler = new DelayedHandler()
        {
            @Override
            protected Response accept(Request request)
            {
                return request.accept();
            }

            @Override
            protected void schedule(Request request, Runnable handle)
            {
                handle.run();
            }
        };

        _server.setHandler(delayedHandler);
        CountDownLatch handling = new CountDownLatch(1);
        delayedHandler.setHandler(new HelloHandler()
        {
            @Override
            public void handle(Request request) throws Exception
            {
                handling.countDown();
                super.handle(request);
            }
        });
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            assertTrue(handling.await(10, TimeUnit.SECONDS));

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
            protected Response accept(Request request)
            {
                return request.accept();
            }

            @Override
            protected void schedule(Request request, Runnable handle)
            {
                try
                {
                    handleEx.exchange(handle);
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }
        };

        _server.setHandler(delayedHandler);
        CountDownLatch handling = new CountDownLatch(1);
        delayedHandler.setHandler(new HelloHandler()
        {
            @Override
            public void handle(Request request) throws Exception
            {
                handling.countDown();
                super.handle(request);
            }
        });
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            Runnable handle = handleEx.exchange(null, 10, TimeUnit.SECONDS);
            assertNotNull(handle);
            assertFalse(handling.await(250, TimeUnit.MILLISECONDS));

            handle.run();

            assertTrue(handling.await(10, TimeUnit.SECONDS));

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
        DelayedHandler delayedHandler = new DelayedHandler.OnContent();

        _server.setHandler(delayedHandler);
        CountDownLatch handling = new CountDownLatch(1);
        delayedHandler.setHandler(new HelloHandler()
        {
            @Override
            public void handle(Request request) throws Exception
            {
                handling.countDown();
                super.handle(request);
            }
        });
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = "POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            assertFalse(handling.await(250, TimeUnit.MILLISECONDS));

            output.write("01234567\r\n".getBytes(StandardCharsets.UTF_8));
            output.flush();

            assertTrue(handling.await(10, TimeUnit.SECONDS));

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
        final int QOS = 3;
        final int EXTRA = 2;

        DelayedHandler delayedHandler = new DelayedHandler.QualityOfService(QOS);
        _server.setHandler(delayedHandler);

        AtomicInteger handling = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        Semaphore semaphore = new Semaphore(0);
        delayedHandler.setHandler(new HelloHandler()
        {
            @Override
            public void handle(Request request) throws Exception
            {
                try
                {
                    handling.incrementAndGet();
                    semaphore.acquire();
                    super.handle(request);
                }
                finally
                {
                    handled.incrementAndGet();
                }
            }
        });
        _server.start();

        Socket[] socket = new Socket[QOS + EXTRA];
        for (int i = 0; i < socket.length; i++)
        {
            socket[i] = new Socket("localhost", _connector.getLocalPort());
            String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
            OutputStream output = socket[i].getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        waitFor(handling, QOS);
        assertThat(handling.get(), is(QOS));
        assertThat(handled.get(), is(0));

        for (int i = 0; i < socket.length; i++)
        {
            semaphore.release();
            int count = i + 1;

            waitFor(handled, count);
            waitFor(handling, QOS + Math.min(EXTRA, count));
            assertThat(handling.get(), is(QOS + Math.min(EXTRA, count)));
            assertThat(handled.get(), is(count));
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
            protected Response accept(Request request)
            {
                return request.accept();
            }

            @Override
            protected void schedule(Request request, Runnable handle)
            {
                request.execute(handle);
            }
        };

        _server.setHandler(delayedHandler);
        delayedHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public void handle(Request request)
            {
                // Not accepted
            }
        });

        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
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
        Exchanger<Runnable> handleEx = new Exchanger<>();
        DelayedHandler delayedHandler = new DelayedHandler()
        {
            @Override
            protected Response accept(Request request)
            {
                return request.accept();
            }

            @Override
            protected void schedule(Request request, Runnable handle)
            {
                request.execute(handle);
            }
        };

        delayedHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public void handle(Request request)
            {
                // Not accepted
            }
        });

        Handler.Collection handlers = new Handler.Collection();
        handlers.setHandlers(delayedHandler, new DefaultHandler());
        _server.setHandler(handlers);

        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
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
