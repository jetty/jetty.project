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

package org.eclipse.jetty.server.session;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConcurrencyTest
 *
 * This test performs multiple concurrent requests from different clients
 * for the same session on the same node.
 */
public class ConcurrencyTest
{
    @Test
    public void testLoad() throws Exception
    {
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        String contextPath = "";
        String servletMapping = "/server";
        TestServer server1 = new TestServer(0, 60, 5, cacheFactory, storeFactory);

        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);

        try
        {
            server1.start();
            int port1 = server1.getPort();

            HttpClient client = new HttpClient();
            client.start();
            try
            {
                String url = "http://localhost:" + port1 + contextPath + servletMapping;

                //create session upfront so the session id is established and
                //can be shared to all clients
                ContentResponse response1 = client.GET(url + "?action=init");
                assertEquals(HttpServletResponse.SC_OK, response1.getStatus());
                String sessionCookie = response1.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                //simulate 10 clients making 10 requests each for the same session
                ExecutorService executor = Executors.newCachedThreadPool();
                int clientsCount = 10;
                CyclicBarrier barrier = new CyclicBarrier(clientsCount + 1);
                int requestsCount = 10;
                Worker[] workers = new Worker[clientsCount];
                for (int i = 0; i < clientsCount; ++i)
                {
                    workers[i] = new Worker(barrier, client, requestsCount, sessionCookie, url);
                    executor.execute(workers[i]);
                }
                // Wait for all workers to be ready
                barrier.await();
                long start = NanoTime.now();

                // Wait for all workers to be done
                barrier.await();
                System.err.println("Elapsed ms:" + NanoTime.millisElapsedFrom(start));
                executor.shutdownNow();

                // Perform one request to get the result - the session
                // should have counted all the requests by incrementing
                // a counter in an attribute.
                Request request = client.newRequest(url + "?action=result");
                ContentResponse response2 = request.send();
                assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
                String response = response2.getContentAsString();
                assertEquals(response.trim(), String.valueOf(clientsCount * requestsCount));
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            server1.stop();
        }
    }

    public static class Worker implements Runnable
    {
        public static int COUNT = 0;

        private final HttpClient client;

        private final CyclicBarrier barrier;

        private final int requestsCount;

        private final String sessionCookie;

        private final String url;

        private final String name;

        public Worker(CyclicBarrier barrier, HttpClient client, int requestsCount, String sessionCookie, String url)
        {
            this.client = client;
            this.barrier = barrier;
            this.requestsCount = requestsCount;
            this.sessionCookie = sessionCookie;
            this.url = url;
            this.name = "" + (COUNT++);
        }

        @Override
        public void run()
        {
            try
            {
                // Wait for all workers to be ready
                barrier.await();

                Random random = new Random();

                for (int i = 0; i < requestsCount; ++i)
                {
                    int pauseMsec = random.nextInt(1000);

                    //wait a random number of milliseconds between requests up to 1 second
                    if (pauseMsec > 0)
                    {
                        Thread.currentThread().sleep(pauseMsec);
                    }
                    Request request = client.newRequest(url + "?action=increment");
                    ContentResponse response = request.send();
                    assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                }

                // Wait for all workers to be done
                barrier.await();
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        }
    }

    public static class TestServlet
        extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("value", 0);
            }
            else if ("increment".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                synchronized (session)
                {
                    int value = (Integer)session.getAttribute("value");
                    session.setAttribute("value", value + 1);
                }
            }
            else if ("result".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                Integer value = null;
                synchronized (session)
                {
                    value = (Integer)session.getAttribute("value");
                }
                PrintWriter writer = response.getWriter();
                writer.println(value);
                writer.flush();
            }
        }
    }
}
