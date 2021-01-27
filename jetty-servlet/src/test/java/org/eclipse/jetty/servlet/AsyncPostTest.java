//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.AsyncContext;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class AsyncPostTest
{
    private static final int THREADS = 10;
    private static final int REQUEST_COUNT = 100;
    private static final int ASYNC_TIMEOUT = 1000;

    private Server server;
    private HttpClient client;
    private ExecutorService servletReadsExecutor;
    private ExecutorService contentProviderExecutor;

    private class ReadOnPostServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
        {
            AsyncContext asyncContext = request.startAsync(request, response);
            asyncContext.setTimeout(ASYNC_TIMEOUT);
            Runnable task = () ->
            {
                try
                {
                    ServletInputStream inputStream = request.getInputStream();
                    byte[] buffer = new byte[64];
                    while (true)
                    {
                        int read = inputStream.read(buffer);
                        if (read == -1)
                            break;
                    }
                }
                catch (IOException e)
                {
                    throw new RuntimeException("io exception", e);
                }
                finally
                {
                    asyncContext.complete();
                }
            };
            servletReadsExecutor.submit(task);
        }
    }

    @BeforeEach
    void setUp() throws Exception
    {
        server = new Server(new QueuedThreadPool(THREADS, THREADS, new LinkedBlockingDeque<>()));
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(8080);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/ctx");
        contextHandler.addServlet(new ServletHolder(new ReadOnPostServlet()), "/readOnPost");

        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[]{contextHandler, new DefaultHandler()});
        server.setHandler(handlers);
        server.start();

        client = new HttpClient();
        client.setMaxRequestsQueuedPerDestination(REQUEST_COUNT);
        client.setExecutor(new QueuedThreadPool(THREADS, THREADS, new LinkedBlockingDeque<>()));
        client.start();

        servletReadsExecutor = Executors.newFixedThreadPool(THREADS);
        contentProviderExecutor = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() throws Exception
    {
        client.stop();
        server.stop();
        contentProviderExecutor.shutdownNow();
        servletReadsExecutor.shutdownNow();
    }

    @Test
    public void blockingReadOnPostWithAsyncTimeout()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++)
            sb.append("|").append(i);
        String content = sb.toString();

        CountDownLatch latch = new CountDownLatch(REQUEST_COUNT);
        AtomicReference<Result> resultRef = new AtomicReference<>();


        for (int i = 0; i < REQUEST_COUNT; i++)
        {
            client.newRequest(server.getURI())
                .path("/ctx/readOnPost")
                .method(HttpMethod.POST)
                .content(new StringDeferredContentProvider(content, StandardCharsets.UTF_8, 1, contentProviderExecutor))
                .timeout(360, TimeUnit.SECONDS)
                .send(result -> latch.countDown());
        }

        try
        {
            assertThat(latch.await(60, TimeUnit.SECONDS), is(true));

            // make sure there are no threads stuck in the servlet code
            servletReadsExecutor.shutdown();
            assertThat(servletReadsExecutor.awaitTermination(5, TimeUnit.SECONDS), is(true));
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException("" + resultRef.get(), e);
        }
        finally
        {
            System.out.println("done.");
        }
    }

    private static class StringDeferredContentProvider extends DeferredContentProvider
    {
        private final byte[] bytes;
        private int index;

        public StringDeferredContentProvider(String content, Charset charset, int delay, ExecutorService executor)
        {
            this.bytes = content.getBytes(charset);
            executor.submit(() ->
            {
                while (true)
                {
                    try
                    {
                        Thread.sleep(delay);
                    }
                    catch (InterruptedException e)
                    {
                        // ignore
                    }
                    if (index == bytes.length)
                    {
                        close();
                        return;
                    }
                    offer(ByteBuffer.wrap(bytes, index++, 1));
                }
            });
        }
    }
}
