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

package org.eclipse.jetty.test.webapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.awaitility.Awaitility.await;

@Tag("stress")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
public class StressHTTP2Test
{
    private static final int N_THREADS = Runtime.getRuntime().availableProcessors() * 2;
    private static final byte[] DATA = """
        [start]
        Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.
        Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure
        dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat
        non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.
        [end]
        """.repeat(50).getBytes(StandardCharsets.UTF_8);

    private ExecutorService executorService;
    private Server server;

    @Test
    public void testOutputWithAborts() throws Exception
    {
        int iterations = 100;
        start(N_THREADS, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setStatus(200);
                response.getOutputStream().write(DATA);
            }
        });

        stress(N_THREADS, (httpClient) ->
        {
            for (int i = 0; i < iterations; i++)
            {
                CompletableFuture<Object> cf = new CompletableFuture<>();
                Request request = httpClient.newRequest(server.getURI());
                request.path("/")
                    .method(HttpMethod.GET)
                    .send(result ->
                    {
                        if (result.isSucceeded())
                            cf.complete(null);
                        else
                            cf.completeExceptionally(result.getFailure());
                    });

                if (i % (iterations / 10) == 0)
                    request.abort(new Exception("client abort"));

                try
                {
                    cf.get();
                }
                catch (Exception e)
                {
                    // ignore
                }

                // if ((j + 1) % 100 == 0)
                //     System.err.println(Thread.currentThread().getName() + " processed " + (j + 1));
            }
        });
    }

    @Test
    public void testInputWithAborts() throws Exception
    {
        int iterations = 100_000;
        start(N_THREADS, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setStatus(200);
                IO.copy(request.getInputStream(), new ByteArrayOutputStream());
            }
        });

        stress(N_THREADS, (httpClient) ->
        {
            for (int i = 0; i < iterations; i++)
            {
                CompletableFuture<Object> cf = new CompletableFuture<>();
                Request request = httpClient.newRequest(server.getURI());
                request.path("/")
                    .method(HttpMethod.POST)
                    .body(new BytesRequestContent(DATA))
                    .send(result ->
                    {
                        if (result.isSucceeded())
                            cf.complete(null);
                        else
                            cf.completeExceptionally(result.getFailure());
                    });

                if (i % (iterations / 10) == 0)
                {
                    request.abort(new Exception("client abort"));
                }

                try
                {
                    cf.get();
                }
                catch (Exception e)
                {
                    // ignore
                }

                // if ((j + 1) % 100 == 0)
                //     System.err.println(Thread.currentThread().getName() + " processed " + (j + 1));
            }
        });
    }

    @AfterEach
    public void tearDown()
    {
        try
        {
            // Assert that no thread is stuck in WAITING state, i.e.: blocked on some lock.
            await().atMost(30, TimeUnit.SECONDS).until(() ->
            {
                TestQueuedThreadPool queuedThreadPool = server.getBean(TestQueuedThreadPool.class);
                for (Thread thread : queuedThreadPool.getCreatedThreads())
                {
                    Thread.State state = thread.getState();
                    if (state == Thread.State.WAITING)
                        return false;
                }
                return true;
            });
        }
        finally
        {
            if (executorService != null)
                executorService.shutdownNow();
            LifeCycle.stop(server);
        }
    }

    private void stress(int threadCount, Job job) throws Exception
    {
        List<Future<?>> futures = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++)
        {
            Future<Object> future = executorService.submit(() ->
            {
                try (HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client())))
                {
                    httpClient.start();

                    job.run(httpClient);
                }
                return null;
            });
            futures.add(future);
        }

        for (Future<?> future : futures)
        {
            future.get();
        }
    }

    private void start(int threadCount, HttpServlet httpServlet) throws Exception
    {
        executorService = Executors.newFixedThreadPool(threadCount);

        QueuedThreadPool qtp = new TestQueuedThreadPool(threadCount);
        server = new Server(qtp);

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.setOutputBufferSize(1);
        ConnectionFactory connectionFactory = new HTTP2CServerConnectionFactory(httpConfiguration);
        ServerConnector serverConnector = new ServerConnector(server, connectionFactory);
        serverConnector.setPort(0);
        server.addConnector(serverConnector);

        ServletContextHandler targetContextHandler = new ServletContextHandler();
        targetContextHandler.setContextPath("/");
        targetContextHandler.addServlet(httpServlet, "/*");

        server.setHandler(targetContextHandler);

        server.start();
    }

    @FunctionalInterface
    private interface Job
    {
        void run(HttpClient httpClient) throws Exception;
    }

    private static class TestQueuedThreadPool extends QueuedThreadPool
    {
        private static final List<Thread> CREATED_THREADS = new CopyOnWriteArrayList<>();
        private static final AtomicInteger COUNTER = new AtomicInteger();
        private static final ThreadFactory THREAD_FACTORY = r ->
        {
            Thread thread = new Thread(r);
            thread.setName("Server-" + COUNTER.incrementAndGet());
            CREATED_THREADS.add(thread);
            return thread;
        };

        public TestQueuedThreadPool(int threadCount)
        {
            super(threadCount * 2, 8, 60000, -1, null, null, THREAD_FACTORY);
        }

        public List<Thread> getCreatedThreads()
        {
            return CREATED_THREADS;
        }
    }
}
