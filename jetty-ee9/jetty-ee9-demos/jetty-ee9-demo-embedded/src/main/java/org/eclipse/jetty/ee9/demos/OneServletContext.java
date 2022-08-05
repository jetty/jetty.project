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

package org.eclipse.jetty.ee9.demos;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.servlet.DefaultServlet;
import org.eclipse.jetty.ee9.servlet.ListenerHolder;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;

import static jakarta.servlet.DispatcherType.ASYNC;
import static jakarta.servlet.DispatcherType.REQUEST;

public class OneServletContext
{
    public static Server createServer(int port, Resource baseResource)
    {
        Server server = new Server(port);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.setBaseResource(baseResource);
        server.setHandler(context);

        // add hello servlet
        context.addServlet(HelloServlet.class, "/hello/*");

        // Add dump servlet on multiple url-patterns
        ServletHolder debugHolder = new ServletHolder("debug", DumpServlet.class);
        context.addServlet(debugHolder, "/dump/*");
        context.addServlet(debugHolder, "*.dump");

        // add default servlet (for error handling and static resources)
        context.addServlet(DefaultServlet.class, "/");

        // sprinkle in a few filters to demonstrate behaviors
        context.addFilter(TestFilter.class, "/test/*", EnumSet.of(REQUEST));
        context.addFilter(TestFilter.class, "*.test", EnumSet.of(REQUEST, ASYNC));

        // and a few listeners to show other ways of working with servlets
        context.getServletHandler().addListener(new ListenerHolder(InitListener.class));
        context.getServletHandler().addListener(new ListenerHolder(RequestListener.class));

        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));

        Server server = createServer(port, Resource.newResource(tempDir));

        server.start();
        server.dumpStdErr();
        server.join();
    }

    public static class TestFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig)
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            if (response instanceof HttpServletResponse)
            {
                HttpServletResponse httpServletResponse = (HttpServletResponse)response;
                httpServletResponse.setHeader("X-TestFilter", "true");
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy()
        {

        }
    }

    public static class InitListener implements ServletContextListener
    {
        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            sce.getServletContext().setAttribute("X-Init", "true");
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
        }
    }

    public static class RequestListener implements ServletRequestListener
    {
        @Override
        public void requestInitialized(ServletRequestEvent sre)
        {
            sre.getServletRequest().setAttribute("X-ReqListener", "true");
        }

        @Override
        public void requestDestroyed(ServletRequestEvent sre)
        {
        }
    }
}
