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

package org.eclipse.jetty.ee9.test;

import java.io.IOException;
import java.util.EnumSet;
import java.util.function.Consumer;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.BufferedResponseHandler;
import org.eclipse.jetty.ee9.servlet.FilterHolder;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests of the nested ee9/ee8 {@link org.eclipse.jetty.ee9.nested.BufferedResponseHandler}
 */
public class BufferedResponseHandlerTest
{
    private Server server;
    private LocalConnector localConnector;

    public void startServer(Consumer<Server> serverConsumer) throws Exception
    {
        Server server = new Server();

        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        serverConsumer.accept(server);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testEmptyFlushThenClose() throws Exception
    {
        startServer((server) ->
        {
            ContextHandlerCollection contexts = new ContextHandlerCollection();
            server.setHandler(contexts);

            ServletContextHandler contextHandler = new ServletContextHandler();
            contextHandler.setContextPath("/a");
            contextHandler.insertHandler(new BufferedResponseHandler());
            contexts.addHandler(contextHandler);
            ServletHolder forwardHolder = new ServletHolder(
                new HttpServlet()
                {
                    @Override
                    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
                    {
                        resp.setStatus(400);
                        resp.flushBuffer();
                        resp.getOutputStream().close();
                    }
                }
            );
            contextHandler.addServlet(forwardHolder, "/test");
        });

        String rawRequest = """
            GET /a/test HTTP/1.1
            Host: local
            Connection: close
            
            """;
        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertEquals(400, response.getStatus());
    }

    /**
     * Setup:
     * - Two contexts
     * - Both contexts on ee9
     * - Both contexts with cross-context enabled
     * - Both contexts with BufferedResponseHandler inserted.
     * - Servlet at /a/forward
     * - Servlet at /b/content
     * Action:
     * - Request to /a/forward initiates a cross-context-dispatch forward to /b/content
     * - Response from /b/content is status 200 with text content
     */
    @Test
    public void testCrossContextDispatchForContentWithBuffers() throws Exception
    {
        startServer((server) ->
        {
            ContextHandlerCollection contexts = new ContextHandlerCollection();
            server.setHandler(contexts);

            ServletContextHandler context1 = new ServletContextHandler();
            context1.setContextPath("/a");
            context1.setCrossContextDispatchSupported(true);
            context1.insertHandler(new BufferedResponseHandler());
            contexts.addHandler(context1);
            ServletHolder forwardHolder = new ServletHolder(
                new HttpServlet()
                {
                    @Override
                    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
                    {
                        ServletContext otherContext = getServletContext().getContext("/b");
                        RequestDispatcher requestDispatcher = otherContext.getRequestDispatcher("/content");
                        requestDispatcher.forward(req, resp);
                    }
                }
            );
            context1.addServlet(forwardHolder, "/forward");

            ServletContextHandler context2 = new ServletContextHandler();
            context2.setContextPath("/b");
            context2.setCrossContextDispatchSupported(true);
            context1.insertHandler(new BufferedResponseHandler());
            contexts.addHandler(context2);
            ServletHolder contentHolder = new ServletHolder(
                new HttpServlet()
                {
                    @Override
                    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
                    {
                        resp.setStatus(200);
                        String content = "As I slowly grow wise I briskly grow cautious.";
                        ServletOutputStream outputStream = resp.getOutputStream();
                        for (int i = 0; i < 100; i++)
                        {
                            outputStream.println(content);
                        }
                    }
                }
            );
            context2.addServlet(contentHolder, "/content");
        });

        String rawRequest = """
            GET /a/forward HTTP/1.1
            Host: local
            Connection: close
            
            """;
        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertEquals(200, response.getStatus());
        String responseBody = response.getContent();
        assertThat(responseBody.length(), greaterThan(500));
        assertThat(responseBody, containsString("As I slowly grow wise I briskly grow cautious."));
    }

    /**
     * Setup:
     * - Two contexts
     * - Both contexts on ee9
     * - Both contexts with cross-context enabled
     * - Both contexts with BufferedResponseHandler inserted.
     * - Servlet at /a/forward
     * - Servlet at /b/bad
     * Action:
     * - Request to /a/forward initiates a cross-context-dispatch forward to /b/bad
     * - Response from /b/bad is status 400 with no body content
     */
    @Test
    public void testCrossContextDispatchForBadRequestWithBuffers() throws Exception
    {
        startServer((server) ->
        {
            ContextHandlerCollection contexts = new ContextHandlerCollection();
            server.setHandler(contexts);

            ServletContextHandler context1 = new ServletContextHandler();
            context1.setContextPath("/a");
            context1.setCrossContextDispatchSupported(true);
            context1.insertHandler(new BufferedResponseHandler());
            contexts.addHandler(context1);
            ServletHolder forwardHolder = new ServletHolder(
                new HttpServlet()
                {
                    @Override
                    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
                    {
                        ServletContext otherContext = getServletContext().getContext("/b");
                        RequestDispatcher requestDispatcher = otherContext.getRequestDispatcher("/bad");
                        requestDispatcher.forward(req, resp);
                    }
                }
            );
            context1.addServlet(forwardHolder, "/forward");

            ServletContextHandler context2 = new ServletContextHandler();
            context2.setContextPath("/b");
            context2.setCrossContextDispatchSupported(true);
            context1.insertHandler(new BufferedResponseHandler());
            contexts.addHandler(context2);
            ServletHolder badRequestHolder = new ServletHolder(
                new HttpServlet()
                {
                    @Override
                    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    {
                        resp.setStatus(400);
                    }
                }
            );
            context2.addServlet(badRequestHolder, "/bad");
        });

        String rawRequest = """
            GET /a/forward HTTP/1.1
            Host: local
            Connection: close
            
            """;
        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertEquals(400, response.getStatus());
        String responseBody = response.getContent();
        assertThat(responseBody.length(), is(0));
    }

    /**
     * Setup:
     * - Two contexts
     * - Both contexts on ee9
     * - Both contexts with cross-context enabled
     * - Both contexts with BufferedResponseHandler inserted.
     * - Servlet at /a/forward
     * - Filter at /a/* - REQUEST dispatcher-type
     * - Servlet at /b/bad
     * Action:
     * - Request to /a/forward is captured by
     * Filter at /a/* which initiates a cross-context-dispatch forward to /b/bad
     * - Response from /b/bad is status 400 with no body content
     */
    @Test
    public void testCrossContextDispatchFromFilterForBadRequestWithBuffers() throws Exception
    {
        startServer((server) ->
        {
            ContextHandlerCollection contexts = new ContextHandlerCollection();
            server.setHandler(contexts);

            ServletContextHandler context1 = new ServletContextHandler();
            context1.setContextPath("/a");
            context1.setCrossContextDispatchSupported(true);
            context1.insertHandler(new BufferedResponseHandler());
            contexts.addHandler(context1);
            ServletHolder helloHolder = new ServletHolder(
                new HttpServlet()
                {
                    @Override
                    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
                    {
                        resp.setStatus(200);
                        resp.getOutputStream().println("Hello"); // shouldn't see this as Filter prevents reaching this.
                    }
                }
            );
            context1.addServlet(helloHolder, "/forward");
            FilterHolder forwardHolder = new FilterHolder(new Filter()
            {
                @Override
                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
                {
                    ServletContext otherContext = request.getServletContext().getContext("/b");
                    RequestDispatcher requestDispatcher = otherContext.getRequestDispatcher("/bad");
                    requestDispatcher.forward(request, response);
                }
            });
            context1.addFilter(forwardHolder, "/*", EnumSet.of(DispatcherType.REQUEST));

            ServletContextHandler context2 = new ServletContextHandler();
            context2.setContextPath("/b");
            context2.setCrossContextDispatchSupported(true);
            context1.insertHandler(new BufferedResponseHandler());
            contexts.addHandler(context2);
            ServletHolder badRequestHolder = new ServletHolder(
                new HttpServlet()
                {
                    @Override
                    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    {
                        resp.setStatus(400);
                    }
                }
            );
            FilterHolder flushFilter = new FilterHolder(new Filter()
            {
                @Override
                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
                {
                    chain.doFilter(request, response);
                    response.flushBuffer();
                }
            });
            context2.addFilter(flushFilter, "/*", EnumSet.of(DispatcherType.FORWARD));
            context2.addServlet(badRequestHolder, "/bad");
        });

        String rawRequest = """
            GET /a/forward HTTP/1.1
            Host: local
            Connection: close
            
            """;
        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertEquals(400, response.getStatus());
        String responseBody = response.getContent();
        assertThat(responseBody.length(), is(0));
    }
}
