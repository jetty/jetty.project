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

package org.eclipse.jetty.ee.servlet;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;

public class HttpOutputBlockingTest
{
    private Server server;
    private ServerConnector connector;
    private ExecutorService executorService;

    @BeforeEach
    public void setUp()
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        executorService = Executors.newFixedThreadPool(16);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        executorService.shutdownNow();
        server.stop();
    }

    @Test
    public void testConcurrentCloseDeadlock() throws Exception
    {
        final int concurrentCloses = 100;
        final int concurrentRequests = 100;
        List<Future<?>> futures = new CopyOnWriteArrayList<>();
        ServletContextHandler handler = new ServletContextHandler("/");
        handler.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                OutputStream out = resp.getOutputStream();
                out.write("OK".getBytes(StandardCharsets.UTF_8));

                for (int i = 0; i < concurrentCloses; i++)
                {
                    Future<Object> f = executorService.submit(() ->
                    {
                        out.close();
                        return null;
                    });
                    futures.add(f);
                }

                for (Future<?> future : futures)
                {
                    try
                    {
                        future.get();
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                }
            }
        }), "/path/*");
        server.setHandler(handler);
        server.start();

        StringBuilder request = new StringBuilder();
        request.append("GET /path/ HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("\r\n");

        int port = connector.getLocalPort();
        try (Socket socket = new Socket("localhost", port))
        {
            socket.setSoTimeout(10000);
            for (int i = 0; i < concurrentRequests; i++)
            {
                OutputStream out = socket.getOutputStream();
                out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));

                HttpTester.Response response = HttpTester.parseResponse(socket.getInputStream());
                assertThat(response, notNullValue());
                assertThat(response.getStatus(), is(200));
                assertThat(response.getContent(), containsString("OK"));
            }
        }
        await().atMost(5, TimeUnit.SECONDS).until(() -> futures.stream().allMatch(Future::isDone));
    }
}
