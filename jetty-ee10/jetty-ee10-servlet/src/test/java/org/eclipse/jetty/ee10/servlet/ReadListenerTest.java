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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ReadListenerTest
{
    private Server server;
    private HttpClient client;

    private void startServer(Consumer<ServletContextHandler> configContext) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        configContext.accept(contextHandler);

        server.setHandler(contextHandler);
        server.start();
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new HttpClient();
        client.setMaxConnectionsPerDestination(10);
        client.start();
    }

    @AfterEach
    public void stopAll()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testLargePost() throws Exception
    {
        final int postDataSize = 10_000_000;

        byte[] postData = new byte[postDataSize];
        ThreadLocalRandom.current().nextBytes(postData);

        startServer((context) ->
        {
            HttpServlet httpServlet = new HttpServlet()
            {
                private static final Logger LOG = LoggerFactory.getLogger(ReadListenerTest.class.getPackageName() + ".testLargePost");

                @Override
                protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
                {
                    final AsyncContext async = request.startAsync();
                    final ServletInputStream inputStream = request.getInputStream();

                    ReadListener readListener = new ReadListener()
                    {
                        private int bytesRead;

                        @Override
                        public void onDataAvailable()
                            throws IOException
                        {
                            byte[] buf = new byte[4096];
                            while (inputStream.isReady() && !inputStream.isFinished())
                            {
                                int readLength = inputStream.read(buf, 0, buf.length);
                                if (readLength == -1)
                                {
                                    break;
                                }
                                bytesRead += readLength;
                            }
                        }

                        @Override
                        public void onAllDataRead()
                        {
//                            LOG.info("onAllDataRead: bytesRead={} contentLength={}", bytesRead, request.getContentLength());
                            if (bytesRead != request.getContentLength())
                                throw new IllegalStateException("BytesRead=%d != contentLength=%d".formatted(bytesRead, request.getContentLength()));
                            async.complete();
                        }

                        @Override
                        public void onError(Throwable throwable)
                        {
                            LOG.error("onError", throwable);
                            async.complete();
                        }
                    };

                    inputStream.setReadListener(readListener);
                }
            };
            ServletHolder servletHolder = context.addServlet(httpServlet, "/readlistener/*");
            servletHolder.setAsyncSupported(true);
        });

        URI uri = server.getURI().resolve("/");

        final int clientRequestsPerThread = 200;
        final int clientThreads = 5;

        ExecutorService executor = Executors.newFixedThreadPool(clientThreads + 1);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < clientThreads; i++)
        {
            tasks.add(new ClientPostTask(client, "/readlistener/", clientRequestsPerThread, uri, postData));
        }

        executor.invokeAll(tasks, 10, TimeUnit.SECONDS);
        executor.shutdown();
    }

    private static class ClientPostTask implements Callable<Void>
    {
        private final HttpClient client;
        private final int requestCount;
        private final URI uri;
        private final String path;
        private final byte[] postData;

        public ClientPostTask(HttpClient client, String path, int requestCount, URI uri, byte[] postData)
        {
            this.client = client;
            this.path = path;
            this.requestCount = requestCount;
            this.uri = uri;
            this.postData = postData;
        }

        @Override
        public Void call() throws Exception
        {
            for (int i = 0; i < requestCount; i++)
            {
                ContentResponse validatedResponse = client.newRequest(uri)
                    .method(HttpMethod.POST)
                    .path(path)
                    .headers((headers) ->
                        headers.put(HttpHeader.CONNECTION, "close"))
                    .body(new BytesRequestContent(postData))
                    .send();
                assertEquals(200, validatedResponse.getStatus());
                assertThat(validatedResponse.getContentAsString(), notNullValue());
            }
            return null;
        }
    }
}
