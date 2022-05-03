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
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FilterHolderTest
 */
public class FilterHolderTest
{
    public static class DummyFilter implements Filter
    {
        public DummyFilter()
        {
        }

        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
        }
    }

    @Test
    public void testInitialize()
        throws Exception
    {
        ServletHandler handler = new ServletHandler();

        final AtomicInteger counter = new AtomicInteger(0);
        Filter filter = new Filter()
        {
            @Override
            public void init(FilterConfig filterConfig)
            {
                counter.incrementAndGet();
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            {
            }

            @Override
            public void destroy()
            {
            }
        };

        FilterHolder fh = new FilterHolder();
        fh.setServletHandler(handler);

        fh.setName("xx");
        fh.setFilter(filter);

        try (StacklessLogging ignored = new StacklessLogging(FilterHolder.class))
        {
            assertThrows(IllegalStateException.class, fh::initialize);
        }

        fh.start();
        fh.initialize();
        assertEquals(1, counter.get());

        fh.initialize();
        assertEquals(1, counter.get());

        fh.stop();
        assertEquals(1, counter.get());
        fh.start();
        assertEquals(1, counter.get());
        fh.initialize();
        assertEquals(2, counter.get());
    }

    @Test
    @Disabled // TODO
    public void testCreateInstance() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(ServletHandler.class, ServletContextHandler.class))
        {
            //test without a ServletContextHandler or current ContextHandler
            FilterHolder holder = new FilterHolder();
            holder.setName("foo");
            holder.setHeldClass(DummyFilter.class);
            Filter filter = holder.createInstance();
            assertNotNull(filter);

            //test with a ServletContextHandler
            Server server = new Server();
            ServletContextHandler context = new ServletContextHandler();
            server.setHandler(context);
            ServletHandler handler = context.getServletHandler();
            handler.addFilter(holder);
            holder.setServletHandler(handler);
            context.start();
            assertNotNull(holder.getFilter());
        }
    }
}
