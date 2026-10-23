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
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * <p>Dispatches made through a {@link RequestDispatcher} obtained directly from another
 * {@link ServletContextHandler}'s own {@link ServletContext}, rather than through
 * {@link ServletContext#getContext(String)}.</p>
 * <p>That yields a plain {@link Dispatcher} whose target context differs from the context the
 * request arrived in, so the dispatched servlet must both see the target context through the request
 * (context path, {@code getServletContext()}, relative {@code getRequestDispatcher()}) and run in the
 * target context's scope (its {@code ClassLoader}, current {@link Context} and scope listeners).
 * Cross context dispatch is deliberately NOT enabled here: this covers the direct handle, not the
 * {@code CrossContextDispatcher} path covered by {@link CrossContextDispatcherTest}.</p>
 */
public class ForeignContextDispatcherTest
{
    private static final String NESTED = "org.eclipse.jetty.test.nested";

    private Server _server;
    private LocalConnector _connector;
    private ServletContextHandler _contextA;
    private ServletContextHandler _contextB;
    private final List<String> _scopeEntersA = new CopyOnWriteArrayList<>();
    private final List<String> _scopeEntersB = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);
        _connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);
        _server.addConnector(_connector);

        ClassLoader parent = ForeignContextDispatcherTest.class.getClassLoader();

        _contextA = new ServletContextHandler();
        _contextA.setContextPath("/a");
        _contextA.setClassLoader(new MarkerClassLoader("/a", parent));
        _contextA.addEventListener(new ScopeRecorder(_scopeEntersA));

        _contextB = new ServletContextHandler();
        _contextB.setContextPath("/b");
        _contextB.setClassLoader(new MarkerClassLoader("/b", parent));
        _contextB.addEventListener(new ScopeRecorder(_scopeEntersB));

        // Both contexts have a /deep/sibling, so a relative dispatch made from within the target
        // says which context it was resolved against.
        _contextA.addServlet(new DumpServlet("SIBLING@a"), "/deep/sibling");
        _contextA.addServlet(new DumpServlet("TARGET@a"), "/deep/target");
        _contextA.addServlet(new DispatchServlet(_contextB), "/deep/dispatch");
        _contextA.addServlet(new DispatchServlet(_contextA), "/deep/local");

        _contextB.addServlet(new DumpServlet("SIBLING@b"), "/deep/sibling");
        _contextB.addServlet(new DumpServlet("TARGET@b"), "/deep/target");
        _contextB.addServlet(new DumpServlet("ERROR@b"), "/deep/error500");
        _contextB.addServlet(new ServletHolder("named", new DumpServlet("NAMED@b")), "/deep/named");

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.addHandler(_contextA);
        contexts.addHandler(_contextB);
        _server.setHandler(contexts);

        _server.start();
        // Ignore any scope entries logged while starting; only the dispatch matters.
        _scopeEntersA.clear();
        _scopeEntersB.clear();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testCrossContextIncludeReportsTargetServletContext() throws Exception
    {
        String content = dispatch("include", "/deep/target", null);
        assertThat(content, containsString("TARGET@b: servletContext=/b"));
    }

    @Test
    public void testCrossContextForwardReportsTargetServletContext() throws Exception
    {
        String content = dispatch("forward", "/deep/target", null);
        assertThat(content, containsString("TARGET@b: servletContext=/b"));
        assertThat(content, containsString("TARGET@b: contextPath=/b"));
        assertThat(content, containsString("TARGET@b: requestURI=/b/deep/target"));
    }

    @Test
    public void testCrossContextForwardRelativeDispatcherResolvesInTargetContext() throws Exception
    {
        String content = dispatch("forward", "/deep/target", "sibling");
        assertThat(content, containsString("SIBLING@b:"));
        assertThat(content, not(containsString("SIBLING@a:")));
    }

    @Test
    public void testCrossContextIncludeRelativeDispatcherResolvesInTargetContext() throws Exception
    {
        String content = dispatch("include", "/deep/target", "sibling");
        assertThat(content, containsString("SIBLING@b:"));
        assertThat(content, not(containsString("SIBLING@a:")));
    }

    @Test
    public void testCrossContextErrorDispatchReportsTargetServletContext() throws Exception
    {
        String content = dispatch("error", "/deep/error500", "sibling");
        assertThat(content, containsString("ERROR@b: servletContext=/b"));
        assertThat(content, containsString("ERROR@b: contextPath=/b"));
        assertThat(content, containsString("ERROR@b: requestURI=/b/deep/error500"));
        assertThat(content, containsString("SIBLING@b:"));
        assertThat(content, not(containsString("SIBLING@a:")));
    }

    @Test
    public void testCrossContextIncludeKeepsOriginalPathElements() throws Exception
    {
        String content = dispatch("include", "/deep/target", null);

        // The context the request was last dispatched to.
        assertThat(content, containsString("TARGET@b: servletContext=/b"));

        // Servlet spec 9.3: an include leaves the request path elements as the includer's.
        assertThat(content, containsString("TARGET@b: contextPath=/a"));
        assertThat(content, containsString("TARGET@b: requestURI=/a/deep/dispatch"));
        assertThat(content, containsString("TARGET@b: servletPath=/deep/dispatch"));

        // But the include attributes describe the target.
        assertThat(content, containsString("TARGET@b: include.contextPath=/b"));
        assertThat(content, containsString("TARGET@b: include.servletPath=/deep/target"));
        assertThat(content, containsString("TARGET@b: include.requestURI=/b/deep/target"));
    }

    @Test
    public void testCrossContextNamedForwardKeepsOriginalPathElements() throws Exception
    {
        String content = dispatch("named", "named", "sibling");

        // A named dispatch does not change the request path, so the context path stays the source's.
        assertThat(content, containsString("NAMED@b: servletContext=/b"));
        assertThat(content, containsString("NAMED@b: contextPath=/a"));
        assertThat(content, containsString("NAMED@b: requestURI=/a/deep/dispatch"));

        // A relative dispatch is still resolved in the target context.
        assertThat(content, containsString("SIBLING@b:"));
        assertThat(content, not(containsString("SIBLING@a:")));
    }

    @Test
    public void testCrossContextForwardEntersTargetClassLoaderAndContext() throws Exception
    {
        String content = dispatch("forward", "/deep/target", null);
        assertThat(content, containsString("TARGET@b: tccl=loader/b"));
        assertThat(content, containsString("TARGET@b: currentContext=/b"));
    }

    @Test
    public void testCrossContextIncludeEntersTargetClassLoaderAndContext() throws Exception
    {
        String content = dispatch("include", "/deep/target", null);
        assertThat(content, containsString("TARGET@b: tccl=loader/b"));
        assertThat(content, containsString("TARGET@b: currentContext=/b"));
    }

    @Test
    public void testCrossContextForwardFiresTargetContextScopeListener() throws Exception
    {
        dispatch("forward", "/deep/target", null);
        // The target context's scope was entered exactly once, for the dispatch.
        assertThat(_scopeEntersB, contains("/b"));
    }

    @Test
    public void testSameContextForwardDoesNotEnterAnotherContext() throws Exception
    {
        String content = dispatchLocal("forward", "/deep/target", "sibling");
        assertThat(content, containsString("TARGET@a: servletContext=/a"));
        assertThat(content, containsString("TARGET@a: contextPath=/a"));
        assertThat(content, containsString("TARGET@a: requestURI=/a/deep/target"));
        assertThat(content, containsString("TARGET@a: tccl=loader/a"));
        assertThat(content, containsString("TARGET@a: currentContext=/a"));
        assertThat(content, containsString("SIBLING@a:"));
        assertThat(content, not(containsString("SIBLING@b:")));

        // A dispatch that stays within one context never enters the other context's scope.
        assertThat(_scopeEntersB, is(empty()));
    }

    @Test
    public void testSameContextIncludeUnchanged() throws Exception
    {
        String content = dispatchLocal("include", "/deep/target", "sibling");
        assertThat(content, containsString("TARGET@a: servletContext=/a"));
        assertThat(content, containsString("TARGET@a: contextPath=/a"));
        assertThat(content, containsString("TARGET@a: requestURI=/a/deep/local"));
        assertThat(content, containsString("TARGET@a: include.contextPath=/a"));
        assertThat(content, containsString("SIBLING@a:"));
        assertThat(content, not(containsString("SIBLING@b:")));
    }

    private String dispatch(String type, String path, String nested) throws Exception
    {
        return request("/a/deep/dispatch", type, path, nested);
    }

    private String dispatchLocal(String type, String path, String nested) throws Exception
    {
        return request("/a/deep/local", type, path, nested);
    }

    private String request(String uri, String type, String path, String nested) throws Exception
    {
        StringBuilder query = new StringBuilder("?type=").append(type).append("&path=").append(path);
        if (nested != null)
            query.append("&nested=").append(nested);

        String rawResponse = _connector.getResponse("""
            GET %s%s HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """.formatted(uri, query));

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpServletResponse.SC_OK));
        return response.getContent();
    }

    /**
     * Dispatches to a target held as a direct {@link ServletContext} reference, which is how
     * applications reach another context without {@code ServletContext.getContext(String)}.
     */
    public static class DispatchServlet extends HttpServlet
    {
        private final ServletContextHandler _target;

        public DispatchServlet(ServletContextHandler target)
        {
            _target = target;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            ServletContext target = _target.getServletContext();
            String nested = request.getParameter("nested");
            if (nested != null)
                request.setAttribute(NESTED, nested);

            String path = request.getParameter("path");
            String type = request.getParameter("type");
            switch (type)
            {
                case "include" -> target.getRequestDispatcher(path).include(request, response);
                case "forward" -> target.getRequestDispatcher(path).forward(request, response);
                case "named" -> target.getNamedDispatcher(path).forward(request, response);
                case "error" -> ((Dispatcher)target.getRequestDispatcher(path)).error(request, response);
                default -> response.sendError(HttpServletResponse.SC_BAD_REQUEST, "unknown type " + type);
            }
        }
    }

    /**
     * Dumps the request's view of its context plus the running scope, optionally after a nested
     * dispatch whose path comes from the {@link #NESTED} attribute.
     */
    public static class DumpServlet extends HttpServlet
    {
        private final String _tag;

        public DumpServlet(String tag)
        {
            _tag = tag;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String nested = (String)request.getAttribute(NESTED);
            if (nested != null)
            {
                request.removeAttribute(NESTED);
                request.getRequestDispatcher(nested).include(request, response);
            }

            Context current = ContextHandler.getCurrentContext();
            PrintWriter out = response.getWriter();
            out.println(_tag + ": servletContext=" + request.getServletContext().getContextPath());
            out.println(_tag + ": contextPath=" + request.getContextPath());
            out.println(_tag + ": requestURI=" + request.getRequestURI());
            out.println(_tag + ": servletPath=" + request.getServletPath());
            out.println(_tag + ": pathInfo=" + request.getPathInfo());
            out.println(_tag + ": dispatcherType=" + request.getDispatcherType());
            out.println(_tag + ": tccl=" + Thread.currentThread().getContextClassLoader());
            out.println(_tag + ": currentContext=" + (current == null ? null : current.getContextPath()));
            out.println(_tag + ": include.contextPath=" + request.getAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH));
            out.println(_tag + ": include.servletPath=" + request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH));
            out.println(_tag + ": include.requestURI=" + request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI));
        }
    }

    /**
     * A recognizable {@link ClassLoader} so a servlet can report which context's loader is current.
     */
    private static class MarkerClassLoader extends ClassLoader
    {
        private final String _tag;

        MarkerClassLoader(String tag, ClassLoader parent)
        {
            super(parent);
            _tag = tag;
        }

        @Override
        public String toString()
        {
            return "loader" + _tag;
        }
    }

    /**
     * Records the context path each time its context scope is entered.
     */
    private static class ScopeRecorder implements ContextHandler.ContextScopeListener
    {
        private final List<String> _enters;

        ScopeRecorder(List<String> enters)
        {
            _enters = enters;
        }

        @Override
        public void enterScope(Context context, Request request)
        {
            _enters.add(context.getContextPath());
        }
    }
}
