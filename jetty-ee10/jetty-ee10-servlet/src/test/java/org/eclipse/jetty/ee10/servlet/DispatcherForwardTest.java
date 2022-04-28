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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringStartsWith.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("serial")
public class DispatcherForwardTest
{
    private Server server;
    private LocalConnector connector;
    private HttpServlet servlet1;
    private HttpServlet servlet2;
    private List<Throwable> failures = new ArrayList<>();

    public void prepare() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");
        context.addServlet(new ServletHolder(servlet1), "/one");
        context.addServlet(new ServletHolder(servlet2), "/two");

        server.start();
    }

    @AfterEach
    public void dispose() throws Throwable
    {
        for (Throwable failure : failures)
        {
            throw failure;
        }
        server.stop();
    }

    // Replacement for Assert that allows to check failures after the response has been sent.
    private <S> void checkThat(S item, Matcher<S> matcher)
    {
        try
        {
            assertThat(item, matcher);
        }
        catch (Throwable th)
        {
            failures.add(th);
        }
    }

    @Test
    public void testQueryRetainedByForwardWithoutQuery() throws Exception
    {
        // 1. request /one?a=1%20one
        // 1. forward /two
        // 2. assert query => a=1 one
        // 1. assert query => a=1 one

        CountDownLatch latch = new CountDownLatch(1);
        final String query1 = "a=1%20one";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query1));

                req.getRequestDispatcher("/two").forward(req, resp);

                checkThat(req.getQueryString(), Matchers.equalTo(query1));
                checkThat(req.getParameter("a"), Matchers.equalTo("1 one"));
                latch.countDown();
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query1));
                checkThat(req.getParameter("a"), Matchers.equalTo("1 one"));
            }
        };

        prepare();

        String request =
            "GET /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = connector.getResponse(request);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
    }

    @Test
    public void testQueryReplacedByForwardWithQuery() throws Exception
    {
        // 1. request /one?a=1
        // 1. forward /two?a=2
        // 2. assert query => a=2
        // 1. assert query => a=1

        CountDownLatch latch = new CountDownLatch(1);
        final String query1 = "a=1%20one&b=2%20two";
        final String query2 = "a=3%20three";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query1));

                req.getRequestDispatcher("/two?" + query2).forward(req, resp);

                checkThat(req.getQueryString(), Matchers.equalTo(query1));
                checkThat(req.getParameter("a"), Matchers.equalTo("1 one"));
                checkThat(req.getParameter("b"), Matchers.equalTo("2 two"));
                latch.countDown();
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query2));
                checkThat(req.getParameter("a"), Matchers.equalTo("3 three"));
                checkThat(req.getParameter("b"), Matchers.equalTo("2 two"));
            }
        };

        prepare();

        String request =
            "GET /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = connector.getResponse(request);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
    }

    @Test
    public void testQueryMergedByForwardWithQuery() throws Exception
    {
        // 1. request /one?a=1
        // 1. forward /two?b=2
        // 2. assert query => b=2
        // 1. assert query => a=1

        CountDownLatch latch = new CountDownLatch(1);
        final String query1 = "a=1%20one";
        final String query2 = "b=2%20two";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query1));

                req.getRequestDispatcher("/two?" + query2).forward(req, resp);

                checkThat(req.getQueryString(), Matchers.equalTo(query1));
                checkThat(req.getParameter("a"), Matchers.equalTo("1 one"));
                latch.countDown();
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query2));
                checkThat(req.getParameter("a"), Matchers.equalTo("1 one"));
                checkThat(req.getParameter("b"), Matchers.equalTo("2 two"));
            }
        };

        prepare();

        String request =
            "GET /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = connector.getResponse(request);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
    }

    @Test
    public void testQueryAggregatesWithFormByForwardWithoutQuery() throws Exception
    {
        // 1. request /one?a=1 + content a=2
        // 1. forward /two
        // 2. assert query => a=1 + params => a=1,2
        // 1. assert query => a=1 + params => a=1,2

        CountDownLatch latch = new CountDownLatch(1);
        final String query1 = "a=1%20one";
        final String form = "a=2%20two";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query1));

                req.getRequestDispatcher("/two").forward(req, resp);

                checkThat(req.getQueryString(), Matchers.equalTo(query1));
                String[] values = req.getParameterValues("a");
                checkThat(values, Matchers.notNullValue());
                checkThat(2, Matchers.equalTo(values.length));
                checkThat(values, Matchers.arrayContainingInAnyOrder("1 one", "2 two"));
                latch.countDown();
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query1));
                String[] values = req.getParameterValues("a");
                checkThat(values, Matchers.notNullValue());
                checkThat(2, Matchers.equalTo(values.length));
                checkThat(values, Matchers.arrayContainingInAnyOrder("1 one", "2 two"));
            }
        };

        prepare();

        String request =
            "POST /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + form.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                form;
        String response = connector.getResponse(request);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(response, startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testQueryAggregatesWithFormReplacedByForwardWithQuery() throws Exception
    {
        // 1. request /one?a=1 + content a=2
        // 1. forward /two?a=3
        // 2. assert query => a=3 + params => a=3,2,1
        // 1. assert query => a=1 + params => a=1,2

        CountDownLatch latch = new CountDownLatch(1);
        final String query1 = "a=1%20one";
        final String query2 = "a=3%20three";
        final String form = "a=2%20two";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query1));

                req.getRequestDispatcher("/two?" + query2).forward(req, resp);

                checkThat(req.getQueryString(), Matchers.equalTo(query1));
                String[] values = req.getParameterValues("a");
                checkThat(values, Matchers.notNullValue());
                checkThat(2, Matchers.equalTo(values.length));
                checkThat(values, Matchers.arrayContainingInAnyOrder("1 one", "2 two"));
                latch.countDown();
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query2));
                String[] values = req.getParameterValues("a");
                checkThat(values, Matchers.notNullValue());
                checkThat(3, Matchers.equalTo(values.length));
                checkThat(values, Matchers.arrayContainingInAnyOrder("3 three", "2 two", "1 one"));
            }
        };

        prepare();

        String request =
            "POST /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + form.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                form;
        String response = connector.getResponse(request);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(response, startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testQueryAggregatesWithFormMergedByForwardWithQuery() throws Exception
    {
        // 1. request /one?a=1 + content b=2
        // 1. forward /two?c=3
        // 2. assert query => c=3 + params => a=1&b=2&c=3
        // 1. assert query => a=1 + params => a=1&b=2

        CountDownLatch latch = new CountDownLatch(1);
        final String query1 = "a=1%20one";
        final String query2 = "c=3%20three";
        final String form = "b=2%20two";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query1));

                req.getRequestDispatcher("/two?" + query2).forward(req, resp);

                checkThat(req.getQueryString(), Matchers.equalTo(query1));
                checkThat(req.getParameter("a"), Matchers.equalTo("1 one"));
                checkThat(req.getParameter("b"), Matchers.equalTo("2 two"));
                checkThat(req.getParameter("c"), Matchers.nullValue());
                latch.countDown();
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query2));
                checkThat(req.getParameter("a"), Matchers.equalTo("1 one"));
                checkThat(req.getParameter("b"), Matchers.equalTo("2 two"));
                checkThat(req.getParameter("c"), Matchers.equalTo("3 three"));
            }
        };

        prepare();

        String request =
            "POST /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + form.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                form;
        String response = connector.getResponse(request);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(response, startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testQueryAggregatesWithFormBeforeAndAfterForward() throws Exception
    {
        // 1. request /one?a=1 + content b=2
        // 1. assert params => a=1&b=2
        // 1. forward /two?c=3
        // 2. assert query => c=3 + params => a=1&b=2&c=3
        // 1. assert query => a=1 + params => a=1&b=2

        CountDownLatch latch = new CountDownLatch(1);
        final String query1 = "a=1%20one";
        final String query2 = "c=3%20three";
        final String form = "b=2%20two";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query1));
                checkThat(req.getParameter("a"), Matchers.equalTo("1 one"));
                checkThat(req.getParameter("b"), Matchers.equalTo("2 two"));

                req.getRequestDispatcher("/two?" + query2).forward(req, resp);

                checkThat(req.getQueryString(), Matchers.equalTo(query1));
                checkThat(req.getParameter("a"), Matchers.equalTo("1 one"));
                checkThat(req.getParameter("b"), Matchers.equalTo("2 two"));
                checkThat(req.getParameter("c"), Matchers.nullValue());
                latch.countDown();
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query2));
                checkThat(req.getParameter("a"), Matchers.equalTo("1 one"));
                checkThat(req.getParameter("b"), Matchers.equalTo("2 two"));
                checkThat(req.getParameter("c"), Matchers.equalTo("3 three"));
            }
        };

        prepare();

        String request =
            "POST /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + form.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                form;
        String response = connector.getResponse(request);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(response, startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testContentCanBeReadViaInputStreamAfterForwardWithoutQuery() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        final String query1 = "a=1%20one";
        final String form = "c=3%20three";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query1));

                req.getRequestDispatcher("/two").forward(req, resp);

                checkThat(req.getQueryString(), Matchers.equalTo(query1));
                checkThat(req.getParameter("c"), Matchers.nullValue());
                latch.countDown();
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query1));
                ServletInputStream input = req.getInputStream();
                for (int i = 0; i < form.length(); ++i)
                {
                    checkThat(form.charAt(i) & 0xFFFF, Matchers.equalTo(input.read()));
                }
            }
        };

        prepare();

        String request =
            "POST /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + form.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                form;
        String response = connector.getResponse(request);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(response, startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testContentCanBeReadViaInputStreamAfterForwardWithQuery() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        final String query1 = "a=1%20one";
        final String query2 = "b=2%20two";
        final String form = "c=3%20three";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query1));

                req.getRequestDispatcher("/two?" + query2).forward(req, resp);

                checkThat(req.getQueryString(), Matchers.equalTo(query1));
                checkThat(req.getParameter("c"), Matchers.nullValue());
                latch.countDown();
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(req.getQueryString(), Matchers.equalTo(query2));
                ServletInputStream input = req.getInputStream();
                for (int i = 0; i < form.length(); ++i)
                {
                    checkThat(form.charAt(i) & 0xFFFF, Matchers.equalTo(input.read()));
                }
                checkThat(-1, Matchers.equalTo(input.read()));
            }
        };

        prepare();

        String request =
            "POST /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + form.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                form;
        String response = connector.getResponse(request);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(response, startsWith("HTTP/1.1 200"));
    }

    // TODO: add multipart tests
}
