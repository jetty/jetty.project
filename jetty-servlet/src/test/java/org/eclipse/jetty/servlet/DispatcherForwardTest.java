//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class DispatcherForwardTest
{
    private Server server;
    private LocalConnector connector;
    private HttpServlet servlet1;
    private HttpServlet servlet2;
    private List<Exception> failures = new ArrayList<>();

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

    @After
    public void dispose() throws Exception
    {
        for (Exception failure : failures)
            throw failure;
        server.stop();
    }

    // Replacement for Assert that allows to check failures after the response has been sent.
    private <S> void checkThat(S item, Matcher<S> matcher)
    {
        if (!matcher.matches(item))
            failures.add(new Exception());
    }

    @Test
    public void testQueryRetainedByForwardWithoutQuery() throws Exception
    {
        // 1. request /one?a=1
        // 1. forward /two
        // 2. assert query => a=1
        // 1. assert query => a=1

        final String query1 = "a=1";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query1, Matchers.equalTo(req.getQueryString()));

                req.getRequestDispatcher("/two").forward(req, resp);

                checkThat(query1, Matchers.equalTo(req.getQueryString()));
                checkThat("1", Matchers.equalTo(req.getParameter("a")));
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query1, Matchers.equalTo(req.getQueryString()));
                checkThat("1", Matchers.equalTo(req.getParameter("a")));
            }
        };

        prepare();

        String request = "" +
                "GET /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = connector.getResponses(request);
        Assert.assertTrue(response, response.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testQueryReplacedByForwardWithQuery() throws Exception
    {
        // 1. request /one?a=1
        // 1. forward /two?a=2
        // 2. assert query => a=2
        // 1. assert query => a=1

        final String query1 = "a=1&b=2";
        final String query2 = "a=3";
        final String query3 = "a=3&b=2";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query1, Matchers.equalTo(req.getQueryString()));

                req.getRequestDispatcher("/two?" + query2).forward(req, resp);

                checkThat(query1, Matchers.equalTo(req.getQueryString()));
                checkThat("1", Matchers.equalTo(req.getParameter("a")));
                checkThat("2", Matchers.equalTo(req.getParameter("b")));
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query3, Matchers.equalTo(req.getQueryString()));
                checkThat("3", Matchers.equalTo(req.getParameter("a")));
                checkThat("2", Matchers.equalTo(req.getParameter("b")));
            }
        };

        prepare();

        String request = "" +
                "GET /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = connector.getResponses(request);
        Assert.assertTrue(response, response.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testQueryMergedByForwardWithQuery() throws Exception
    {
        // 1. request /one?a=1
        // 1. forward /two?b=2
        // 2. assert query => a=1&b=2
        // 1. assert query => a=1

        final String query1 = "a=1";
        final String query2 = "b=2";
        final String query3 = "b=2&a=1";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query1, Matchers.equalTo(req.getQueryString()));

                req.getRequestDispatcher("/two?" + query2).forward(req, resp);

                checkThat(query1, Matchers.equalTo(req.getQueryString()));
                checkThat("1", Matchers.equalTo(req.getParameter("a")));
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query3, Matchers.equalTo(req.getQueryString()));
                checkThat("1", Matchers.equalTo(req.getParameter("a")));
                checkThat("2", Matchers.equalTo(req.getParameter("b")));
            }
        };

        prepare();

        String request = "" +
                "GET /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = connector.getResponses(request);
        Assert.assertTrue(response, response.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testQueryAggregatesWithFormByForwardWithoutQuery() throws Exception
    {
        // 1. request /one?a=1 + content a=2
        // 1. forward /two
        // 2. assert query => a=1 + params => a=1,2
        // 1. assert query => a=1 + params => a=1,2

        final String query1 = "a=1";
        final String form = "a=2";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query1, Matchers.equalTo(req.getQueryString()));

                req.getRequestDispatcher("/two").forward(req, resp);

                checkThat(query1, Matchers.equalTo(req.getQueryString()));
                String[] values = req.getParameterValues("a");
                checkThat(values, Matchers.notNullValue());
                checkThat(2, Matchers.equalTo(values.length));
                checkThat(values, Matchers.arrayContainingInAnyOrder("1", "2"));
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query1, Matchers.equalTo(req.getQueryString()));
                String[] values = req.getParameterValues("a");
                checkThat(values, Matchers.notNullValue());
                checkThat(2, Matchers.equalTo(values.length));
                checkThat(values, Matchers.arrayContainingInAnyOrder("1", "2"));
            }
        };

        prepare();

        String request = "" +
                "POST /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + form.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                form;
        String response = connector.getResponses(request);
        Assert.assertTrue(response, response.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testQueryAggregatesWithFormReplacedByForwardWithQuery() throws Exception
    {
        // 1. request /one?a=1 + content a=2
        // 1. forward /two?a=3
        // 2. assert query => a=3 + params => a=3,2,1
        // 1. assert query => a=1 + params => a=1,2

        final String query1 = "a=1";
        final String query2 = "a=3";
        final String form = "a=2";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query1, Matchers.equalTo(req.getQueryString()));

                req.getRequestDispatcher("/two?" + query2).forward(req, resp);

                checkThat(query1, Matchers.equalTo(req.getQueryString()));
                String[] values = req.getParameterValues("a");
                checkThat(values, Matchers.notNullValue());
                checkThat(2, Matchers.equalTo(values.length));
                checkThat(values, Matchers.arrayContainingInAnyOrder("1", "2"));
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query2, Matchers.equalTo(req.getQueryString()));
                String[] values = req.getParameterValues("a");
                checkThat(values, Matchers.notNullValue());
                checkThat(3, Matchers.equalTo(values.length));
                checkThat(values, Matchers.arrayContainingInAnyOrder("3", "2", "1"));
            }
        };

        prepare();

        String request = "" +
                "POST /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + form.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                form;
        String response = connector.getResponses(request);
        Assert.assertTrue(response, response.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testQueryAggregatesWithFormMergedByForwardWithQuery() throws Exception
    {
        // 1. request /one?a=1 + content b=2
        // 1. forward /two?c=3
        // 2. assert query => a=1&c=3 + params => a=1&b=2&c=3
        // 1. assert query => a=1 + params => a=1&b=2

        final String query1 = "a=1";
        final String query2 = "c=3";
        final String query3 = "c=3&a=1";
        final String form = "b=2";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query1, Matchers.equalTo(req.getQueryString()));

                req.getRequestDispatcher("/two?" + query2).forward(req, resp);

                checkThat(query1, Matchers.equalTo(req.getQueryString()));
                checkThat("1", Matchers.equalTo(req.getParameter("a")));
                checkThat("2", Matchers.equalTo(req.getParameter("b")));
                checkThat(req.getParameter("c"), Matchers.nullValue());
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query3, Matchers.equalTo(req.getQueryString()));
                checkThat("1", Matchers.equalTo(req.getParameter("a")));
                checkThat("2", Matchers.equalTo(req.getParameter("b")));
                checkThat("3", Matchers.equalTo(req.getParameter("c")));
            }
        };

        prepare();

        String request = "" +
                "POST /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + form.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                form;
        String response = connector.getResponses(request);
        Assert.assertTrue(response, response.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testQueryAggregatesWithFormBeforeAndAfterForward() throws Exception
    {
        // 1. request /one?a=1 + content b=2
        // 1. assert params => a=1&b=2
        // 1. forward /two?c=3
        // 2. assert query => a=1&c=3 + params => a=1&b=2&c=3
        // 1. assert query => a=1 + params => a=1&b=2

        final String query1 = "a=1";
        final String query2 = "c=3";
        final String query3 = "c=3&a=1";
        final String form = "b=2";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query1, Matchers.equalTo(req.getQueryString()));
                checkThat("1", Matchers.equalTo(req.getParameter("a")));
                checkThat("2", Matchers.equalTo(req.getParameter("b")));

                req.getRequestDispatcher("/two?" + query2).forward(req, resp);

                checkThat(query1, Matchers.equalTo(req.getQueryString()));
                checkThat("1", Matchers.equalTo(req.getParameter("a")));
                checkThat("2", Matchers.equalTo(req.getParameter("b")));
                checkThat(req.getParameter("c"), Matchers.nullValue());
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query3, Matchers.equalTo(req.getQueryString()));
                checkThat("1", Matchers.equalTo(req.getParameter("a")));
                checkThat("2", Matchers.equalTo(req.getParameter("b")));
                checkThat("3", Matchers.equalTo(req.getParameter("c")));
            }
        };

        prepare();

        String request = "" +
                "POST /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + form.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                form;
        String response = connector.getResponses(request);
        Assert.assertTrue(response, response.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testContentCanBeReadViaInputStreamAfterForwardWithoutQuery() throws Exception
    {
        final String query1 = "a=1";
        final String form = "c=3";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query1, Matchers.equalTo(req.getQueryString()));

                req.getRequestDispatcher("/two").forward(req, resp);

                checkThat(query1, Matchers.equalTo(req.getQueryString()));
                checkThat(req.getParameter("c"), Matchers.nullValue());
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query1, Matchers.equalTo(req.getQueryString()));
                ServletInputStream input = req.getInputStream();
                for (int i = 0; i < form.length(); ++i)
                    checkThat(form.charAt(i) & 0xFFFF, Matchers.equalTo(input.read()));
            }
        };

        prepare();

        String request = "" +
                "POST /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + form.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                form;
        String response = connector.getResponses(request);
        Assert.assertTrue(response, response.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testContentCanBeReadViaInputStreamAfterForwardWithQuery() throws Exception
    {
        final String query1 = "a=1";
        final String query2 = "b=2";
        final String query3 = "b=2&a=1";
        final String form = "c=3";
        servlet1 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query1, Matchers.equalTo(req.getQueryString()));

                req.getRequestDispatcher("/two?" + query2).forward(req, resp);

                checkThat(query1, Matchers.equalTo(req.getQueryString()));
                checkThat(req.getParameter("c"), Matchers.nullValue());
            }
        };
        servlet2 = new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                checkThat(query3, Matchers.equalTo(req.getQueryString()));
                ServletInputStream input = req.getInputStream();
                for (int i = 0; i < form.length(); ++i)
                    checkThat(form.charAt(i) & 0xFFFF, Matchers.equalTo(input.read()));
                checkThat(-1, Matchers.equalTo(input.read()));
            }
        };

        prepare();

        String request = "" +
                "POST /one?" + query1 + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + form.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                form;
        String response = connector.getResponses(request);
        Assert.assertTrue(response, response.startsWith("HTTP/1.1 200"));
    }

    // TODO: add multipart tests
}
