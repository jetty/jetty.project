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

package org.eclipse.jetty.ee10.jersey.tests;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.servlet.ServletContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HungBlockingThreadsTest
{
    private static final Logger LOG = LoggerFactory.getLogger(HungBlockingThreadsTest.class);
    private static final int THREAD_COUNT = 512;
    private Server server;
    private ExecutorService executorService;
    private HttpClient httpClient;

    @BeforeAll
    public static void beforeAll()
    {
        // Wire up java.util.logging to slf4j.
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();
    }

    @AfterAll
    public static void afterAll()
    {
        org.slf4j.bridge.SLF4JBridgeHandler.uninstall();
    }

    @BeforeEach
    public void setUp() throws Exception
    {
        QueuedThreadPool queuedThreadPool = new QueuedThreadPool(10, 10, (int)TimeUnit.MINUTES.toMillis(1000L));
        queuedThreadPool.setName("server");
        queuedThreadPool.setDaemon(true);
        queuedThreadPool.setReservedThreads(1);

        server = new Server(queuedThreadPool);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        ResourceConfig resourceConfig = new ResourceConfig();
        Resource resource = Resource.builder(PostResource.class).build();
        Resource.Builder resourceBuilder = Resource.builder(resource);
        resource.getResourceMethods().forEach(resourceMethod -> resourceBuilder.updateMethod(resourceMethod).managedAsync());
        resourceConfig.registerResources(resourceBuilder.build());
        resourceConfig.register(CustomFeature.class);

        context.addServlet(new ServletHolder(new ServletContainer(resourceConfig)), "/*");

        server.setHandler(context);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        connector.setIdleTimeout(Long.MAX_VALUE);
        server.addConnector(connector);
        server.start();

        httpClient = new HttpClient();
        QueuedThreadPool clientQtp = new QueuedThreadPool(THREAD_COUNT * 2);
        clientQtp.setName("client");
        httpClient.setExecutor(clientQtp);
        httpClient.setMaxConnectionsPerDestination(THREAD_COUNT);
        httpClient.start();

        executorService = Executors.newCachedThreadPool();
    }

    @AfterEach
    public void tearDown()
    {
        executorService.shutdownNow();
        LifeCycle.stop(server);
        LifeCycle.stop(httpClient);
    }

    /**
     * This test tries to reproduce issue #13066 by running THREAD_COUNT parallel requests
     * in the hope of reproducing the issue statistically.
     */
    @Tag("stress")
    @Test
    public void test() throws Exception
    {
        ByteBuffer data = ByteBuffer.wrap("A".repeat(1024).getBytes(StandardCharsets.UTF_8));
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++)
        {
            executorService.submit(() ->
            {
                AsyncRequestContent content = new AsyncRequestContent("text/plain", data.slice());
                Request request = httpClient.POST(server.getURI());
                request
                    .onRequestListener(new Request.Listener()
                    {
                        @Override
                        public void onCommit(Request request)
                        {
                            latch.countDown();
                        }
                    })
                    .body(content)
                    .timeout(60, TimeUnit.SECONDS)
                    .send(result -> {});
            });
        }

        LOG.debug("Awaiting for client to block on all threads");
        assertTrue(latch.await(15, TimeUnit.SECONDS));

        LOG.debug("Shutting down client");
        // While the server is hanging in InputStream read, close the client's connections.
        httpClient.stop();

        LOG.debug("Waiting for hung threads");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(threadDump(), not(containsString(Blocker.Shared.class.getName()))));
    }

    private static String threadDump()
    {
        StringBuilder threadDump = new StringBuilder(System.lineSeparator());
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        for (ThreadInfo threadInfo : threadMXBean.dumpAllThreads(true, true))
        {
            threadDump.append(threadInfo.toString());
        }
        return threadDump.toString();
    }

    public static class CustomFeature implements Feature
    {
        @Override
        public boolean configure(FeatureContext context)
        {
            context.register(JettyEofExceptionMapper.class);
            return true;
        }
    }

    public static class JettyEofExceptionMapper implements ExceptionMapper<EofException>
    {
        @Override
        public Response toResponse(EofException e)
        {
            Response.Status status = Response.Status.BAD_REQUEST;
            return Response.status((Response.StatusType)status)
                .type(MediaType.APPLICATION_JSON + "; " + MediaType.CHARSET_PARAMETER + "=utf-8")
                .entity(status.getReasonPhrase()).build();
        }
    }

    @Path("")
    public static class PostResource
    {
        @POST
        @Consumes("text/plain")
        public Response doPost(String body)
        {
            return Response.ok().entity(body).build();
        }
    }
}
