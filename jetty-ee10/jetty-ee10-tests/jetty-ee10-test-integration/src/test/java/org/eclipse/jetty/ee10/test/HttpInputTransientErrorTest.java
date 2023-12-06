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

package org.eclipse.jetty.ee10.test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

//TODO test all protocols
public class HttpInputTransientErrorTest
{
    private static final int IDLE_TIMEOUT = 250;

    private LocalConnector connector;
    private Server server;
    private ArrayByteBufferPool.Tracking bufferPool;

    @AfterEach
    public void tearDown()
    {
        try
        {
            if (bufferPool != null)
                assertThat("Server leaks: " + bufferPool.dumpLeaks(), bufferPool.getLeaks().size(), is(0));
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    private void startServer(HttpServlet servlet) throws Exception
    {
        bufferPool = new ArrayByteBufferPool.Tracking();
        server = new Server(null, null, bufferPool);
        connector = new LocalConnector(server, new HttpConnectionFactory());
        connector.setIdleTimeout(IDLE_TIMEOUT);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler("/ctx");
        server.setHandler(context);
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder, "/*");

        server.start();
    }

    @Test
    public void testAsyncServletHandleError() throws Exception
    {
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                AsyncContext asyncContext = req.startAsync(req, resp);
                asyncContext.setTimeout(0);
                resp.setContentType("text/plain;charset=UTF-8");

                // Since the client sends a request with a content-length header, but sends
                // the content only after idle timeout expired, this ReadListener will have
                // onError() executed first, then since onError() charges on and reads the content,
                // onDataAvailable and onAllDataRead are called afterwards.
                req.getInputStream().setReadListener(new ReadListener()
                {
                    final AtomicInteger counter = new AtomicInteger();

                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        ServletInputStream input = req.getInputStream();
                        while (true)
                        {
                            if (!input.isReady())
                                break;
                            int read = input.read();
                            if (read < 0)
                                break;
                            else
                                counter.incrementAndGet();
                        }
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        events.add("onAllDataRead");
                        resp.setStatus(HttpStatus.OK_200);
                        resp.setContentType("text/plain;charset=UTF-8");
                        resp.getWriter().println("read=" + counter.get());
                        asyncContext.complete();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        events.add("onError");
                        if (failure.compareAndSet(null, t))
                        {
                            try
                            {
                                // The first error is transient, just try to read normally.
                                onDataAvailable();
                            }
                            catch (IOException e)
                            {
                                resp.setStatus(599);
                                asyncContext.complete();
                            }
                        }
                        else
                        {
                            resp.setStatus(598);
                            t.printStackTrace();
                            asyncContext.complete();
                        }
                    }
                });
            }
        });

        try (LocalConnector.LocalEndPoint localEndPoint = connector.connect())
        {
            String request = """
                POST /ctx/post HTTP/1.1
                Host: local
                Content-Length: 10
                            
                """;
            localEndPoint.addInput(request);
            Thread.sleep((long)(IDLE_TIMEOUT * 1.5));
            localEndPoint.addInput("1234567890");
            HttpTester.Response response = HttpTester.parseResponse(localEndPoint.getResponse(false, 5, TimeUnit.SECONDS));

            assertThat("Unexpected response status\n" + response + response.getContent(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.get(HttpHeader.CONNECTION), nullValue());
            assertThat(response.get(HttpHeader.CONTENT_TYPE), is("text/plain;charset=UTF-8"));
            assertThat(response.getContent(), containsString("read=10"));
            assertInstanceOf(TimeoutException.class, failure.get());
            assertThat(events, contains("onError", "onAllDataRead"));
        }
    }

    @Test
    public void testAsyncTimeoutThenSetReadListenerThenRead() throws Exception
    {
        CountDownLatch doPostlatch = new CountDownLatch(1);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            {
                AsyncContext asyncContext = req.startAsync(req, resp);
                asyncContext.setTimeout(0);
                resp.setContentType("text/plain;charset=UTF-8");

                // Not calling setReadListener will make Jetty set the ServletChannelState
                // in state WAITING upon doPost return, so idle timeouts are ignored.
                new Thread(() ->
                {
                    try
                    {
                        doPostlatch.await(5, TimeUnit.SECONDS);

                        req.getInputStream().setReadListener(new ReadListener()
                        {
                            final AtomicInteger counter = new AtomicInteger();

                            @Override
                            public void onDataAvailable() throws IOException
                            {
                                ServletInputStream input = req.getInputStream();
                                while (true)
                                {
                                    if (!input.isReady())
                                        break;
                                    int read = input.read();
                                    if (read < 0)
                                        break;
                                    else
                                        counter.incrementAndGet();
                                }
                            }

                            @Override
                            public void onAllDataRead() throws IOException
                            {
                                resp.setStatus(HttpStatus.OK_200);
                                resp.setContentType("text/plain;charset=UTF-8");
                                resp.getWriter().println("read=" + counter.get());
                                asyncContext.complete();
                            }

                            @Override
                            public void onError(Throwable t)
                            {
                                failure.set(t);
                                resp.setStatus(598);
                                t.printStackTrace();
                                asyncContext.complete();
                            }
                        });
                    }
                    catch (Exception e)
                    {
                        throw new AssertionError(e);
                    }
                }).start();
            }
        });

        try (LocalConnector.LocalEndPoint localEndPoint = connector.connect())
        {
            String request = """
                POST /ctx/post HTTP/1.1
                Host: local
                Content-Length: 10
                            
                """;
            localEndPoint.addInput(request);
            Thread.sleep((long)(IDLE_TIMEOUT * 1.5));
            localEndPoint.addInput("1234567890");
            doPostlatch.countDown();
            HttpTester.Response response = HttpTester.parseResponse(localEndPoint.getResponse(false, 5, TimeUnit.SECONDS));

            assertThat("Unexpected response status\n" + response + response.getContent(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.get(HttpHeader.CONTENT_TYPE), is("text/plain;charset=UTF-8"));
            assertThat(response.getContent(), containsString("read=10"));
            assertThat(failure.get(), nullValue());
        }
    }

    @Test
    public void testAsyncServletStopOnError() throws Exception
    {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                AsyncContext asyncContext = req.startAsync(req, resp);
                asyncContext.setTimeout(0);
                resp.setContentType("text/plain;charset=UTF-8");

                req.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {
                        throw new AssertionError();
                    }

                    @Override
                    public void onAllDataRead()
                    {
                        throw new AssertionError();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        if (failure.compareAndSet(null, t))
                        {
                            resp.setStatus(HttpStatus.IM_A_TEAPOT_418);
                            asyncContext.complete();
                        }
                        else
                        {
                            resp.setStatus(599);
                            t.printStackTrace();
                            asyncContext.complete();
                        }
                    }
                });
            }
        });

        try (LocalConnector.LocalEndPoint localEndPoint = connector.connect())
        {
            String request = """
                POST /ctx/post HTTP/1.1
                Host: local
                Content-Length: 10
                            
                """;
            localEndPoint.addInput(request);
            Thread.sleep((long)(IDLE_TIMEOUT * 1.5));
            localEndPoint.addInput("1234567890");
            HttpTester.Response response = HttpTester.parseResponse(localEndPoint.getResponse(false, 5, TimeUnit.SECONDS));

            assertThat("Unexpected response status\n" + response + response.getContent(), response.getStatus(), is(HttpStatus.IM_A_TEAPOT_418));
            assertInstanceOf(TimeoutException.class, failure.get());
        }
    }

    @Test
    public void testBlockingServletHandleError() throws Exception
    {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                try
                {
                    IO.toString(req.getInputStream());
                }
                catch (IOException e)
                {
                    failure.set(e);
                }

                String content = IO.toString(req.getInputStream());
                resp.setStatus(HttpStatus.OK_200);
                resp.setContentType("text/plain;charset=UTF-8");
                resp.getWriter().println("read=" + content.length());
            }
        });

        try (LocalConnector.LocalEndPoint localEndPoint = connector.connect())
        {
            String request = """
                POST /ctx/post HTTP/1.1
                Host: local
                Content-Length: 10
                            
                """;
            localEndPoint.addInput(request);
            Thread.sleep((long)(IDLE_TIMEOUT * 1.5));
            localEndPoint.addInput("1234567890");
            HttpTester.Response response = HttpTester.parseResponse(localEndPoint.getResponse(false, 5, TimeUnit.SECONDS));

            assertThat("Unexpected response status\n" + response + response.getContent(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.get(HttpHeader.CONTENT_TYPE), is("text/plain;charset=UTF-8"));
            assertThat(response.getContent(), containsString("read=10"));
            assertInstanceOf(IOException.class, failure.get());
            assertInstanceOf(TimeoutException.class, failure.get().getCause());
        }
    }

    @Test
    public void testBlockingServletStopOnError() throws Exception
    {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            {
                try
                {
                    IO.toString(req.getInputStream());
                }
                catch (IOException e)
                {
                    failure.set(e);
                    resp.setStatus(HttpStatus.IM_A_TEAPOT_418);
                }
            }
        });

        try (LocalConnector.LocalEndPoint localEndPoint = connector.connect())
        {
            String request = """
                POST /ctx/post HTTP/1.1
                Host: local
                Content-Length: 10
                            
                """;
            localEndPoint.addInput(request);
            Thread.sleep((long)(IDLE_TIMEOUT * 1.5));
            localEndPoint.addInput("1234567890");
            HttpTester.Response response = HttpTester.parseResponse(localEndPoint.getResponse(false, 5, TimeUnit.SECONDS));

            assertThat("Unexpected response status\n" + response + response.getContent(), response.getStatus(), is(HttpStatus.IM_A_TEAPOT_418));
            assertInstanceOf(IOException.class, failure.get());
            assertInstanceOf(TimeoutException.class, failure.get().getCause());
        }
    }
}
