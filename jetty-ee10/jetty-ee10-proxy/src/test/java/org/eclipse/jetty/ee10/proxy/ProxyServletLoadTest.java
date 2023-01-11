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

package org.eclipse.jetty.ee10.proxy;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyServletLoadTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(
            ProxyServlet.class,
            AsyncProxyServlet.class,
            AsyncMiddleManServlet.class)
            .map(Arguments::of);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ProxyServletLoadTest.class);
    private static final String PROXIED_HEADER = "X-Proxied";

    private HttpClient client;
    private Server proxy;
    private ServerConnector proxyConnector;
    private AbstractProxyServlet proxyServlet;
    private Server server;
    private ServerConnector serverConnector;

    private void startServer(Class<? extends AbstractProxyServlet> proxyServletClass, HttpServlet servlet) throws Exception
    {
        proxyServlet = proxyServletClass.getDeclaredConstructor().newInstance();

        QueuedThreadPool serverPool = new QueuedThreadPool();
        serverPool.setName("server");
        server = new Server(serverPool);
        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);

        ServletContextHandler appCtx = new ServletContextHandler(server, "/", true, false);
        ServletHolder appServletHolder = new ServletHolder(servlet);
        appCtx.addServlet(appServletHolder, "/*");

        server.start();
    }

    private void startProxy() throws Exception
    {
        QueuedThreadPool proxyPool = new QueuedThreadPool();
        proxyPool.setName("proxy");
        proxy = new Server(proxyPool);

        HttpConfiguration configuration = new HttpConfiguration();
        configuration.setSendDateHeader(false);
        configuration.setSendServerVersion(false);
        proxyConnector = new ServerConnector(proxy, new HttpConnectionFactory(configuration));
        proxy.addConnector(proxyConnector);

        ServletContextHandler proxyContext = new ServletContextHandler(proxy, "/", true, false);
        ServletHolder proxyServletHolder = new ServletHolder(proxyServlet);
        proxyContext.addServlet(proxyServletHolder, "/*");

        proxy.start();
    }

    private void startClient() throws Exception
    {
        QueuedThreadPool clientPool = new QueuedThreadPool();
        clientPool.setName("client");
        HttpClient result = new HttpClient();
        result.setExecutor(clientPool);
        result.getProxyConfiguration().addProxy(new HttpProxy("localhost", proxyConnector.getLocalPort()));
        result.start();
        client = result;
    }

    @AfterEach
    public void dispose() throws Exception
    {
        client.stop();
        proxy.stop();
        server.stop();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void test(Class<? extends AbstractProxyServlet> proxyServletClass) throws Exception
    {
        startServer(proxyServletClass, new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                IO.copy(req.getInputStream(), resp.getOutputStream());
            }
        });
        startProxy();
        startClient();

        // Number of clients to simulate
        int clientCount = ProcessorUtils.availableProcessors();

        // Latch for number of clients still active (used to terminate test)
        final CountDownLatch activeClientLatch = new CountDownLatch(clientCount);

        // Atomic Boolean to track that its OK to still continue looping.
        // When this goes false, that means one of the client threads has
        // encountered an error condition, and should allow all remaining
        // client threads to finish cleanly.
        final AtomicBoolean success = new AtomicBoolean(true);

        int iterations = 1000;

        // Start clients
        for (int i = 0; i < clientCount; i++)
        {
            ClientLoop r = new ClientLoop(activeClientLatch, success, client, "localhost", serverConnector.getLocalPort(), iterations, i);
            String name = "client-" + i;
            Thread thread = new Thread(r, name);
            thread.start();
        }

        assertTrue(activeClientLatch.await(Math.max(clientCount * iterations * 10, 5000), TimeUnit.MILLISECONDS));
        assertTrue(success.get());
    }

    private static class ClientLoop implements Runnable
    {
        private final CountDownLatch active;
        private final AtomicBoolean success;
        private final HttpClient client;
        private final String host;
        private final int port;
        private int iterations;
        private final int idx;

        public ClientLoop(CountDownLatch activeClientLatch, AtomicBoolean success, HttpClient client, String serverHost, int serverPort, int iterations, int idx)
        {
            this.active = activeClientLatch;
            this.success = success;
            this.client = client;
            this.host = serverHost;
            this.port = serverPort;
            this.iterations = iterations;
            this.idx = idx;
        }

        @Override
        public void run()
        {
            String threadName = Thread.currentThread().getName();
            LOG.info("Starting thread {}", threadName);
            try
            {
                while (success.get())
                {
                    --iterations;

                    byte[] content = new byte[1024];
                    new Random().nextBytes(content);
                    ContentResponse response = client.newRequest(host, port).method(HttpMethod.POST).path("/" + iterations + "-" + idx).body(new BytesRequestContent(content))
                        .timeout(5, TimeUnit.SECONDS).send();

                    if (response.getStatus() != 200)
                    {
                        LOG.warn("Got response <{}>, expecting <{}> iteration={}-{}", response.getStatus(), 200, iterations, idx);
                        // allow all ClientLoops to finish
                        success.set(false);
                    }
                    else
                    {
                        if (iterations == 0)
                            break;
                    }
                }
            }
            catch (Throwable x)
            {
                LOG.warn("Error processing request {}-{}", iterations, idx, x);
                success.set(false);
            }
            finally
            {
                LOG.info("Shutting down thread {}", threadName);
                active.countDown();
            }
        }
    }
}
