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

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InitServletTest
{
    public static class DemoServlet extends HttpServlet
    {
        private static final long INIT_SLEEP = 2000;
        private final AtomicInteger initCount = new AtomicInteger();

        @Override
        public void init() throws ServletException
        {
            super.init();
            try
            {
                // Make the initialization last a little while.
                // Other requests must wait.
                Thread.sleep(INIT_SLEEP);
            }
            catch (InterruptedException e)
            {
                throw new ServletException(e);
            }
            initCount.incrementAndGet();
        }

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            // Check that the init() method has been totally finished (by another request)
            // before the servlet service() method is called.
            if (initCount.get() != 1)
                resp.sendError(500, "Servlet not initialized!");
        }
    }

    private static class AsyncResponseListener implements Response.CompleteListener
    {
        private final AtomicInteger index = new AtomicInteger();
        private final CountDownLatch resultsLatch;
        private final int[] results;

        public AsyncResponseListener(CountDownLatch resultsLatch, int[] results)
        {
            this.resultsLatch = resultsLatch;
            this.results = results;
        }

        public void onComplete(Result result)
        {
            results[index.getAndIncrement()] = result.getResponse().getStatus();
            resultsLatch.countDown();
        }
    }

    @Test
    public void testServletInitialization() throws Exception
    {
        Server server = new Server(0);
        ServletContextHandler context = new ServletContextHandler(server, "/");
        server.setHandler(context);
        // Add a lazily instantiated servlet.
        context.addServlet(new ServletHolder(DemoServlet.class), "/*");
        HttpClient client = new HttpClient();
        server.addBean(client);
        server.start();
        try
        {
            int port = ((NetworkConnector)server.getConnectors()[0]).getLocalPort();

            // Expect 2 responses
            CountDownLatch resultsLatch = new CountDownLatch(2);
            int[] results = new int[2];
            AsyncResponseListener l = new AsyncResponseListener(resultsLatch, results);

            // Req1: should initialize servlet.
            client.newRequest("http://localhost:" + port + "/r1").send(l);

            // Need to give 1st request a head start before request2.
            Thread.sleep(DemoServlet.INIT_SLEEP / 4);

            // Req2: should see servlet fully initialized by request1.
            client.newRequest("http://localhost:" + port + "/r2").send(l);

            assertTrue(resultsLatch.await(DemoServlet.INIT_SLEEP * 2, TimeUnit.MILLISECONDS));
            assertEquals(HttpStatus.OK_200, results[0]);
            assertEquals(HttpStatus.OK_200, results[1]);
        }
        finally
        {
            server.stop();
        }
    }
}
