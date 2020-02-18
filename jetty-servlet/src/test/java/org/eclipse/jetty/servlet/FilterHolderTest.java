//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.logging.StacklessLogging;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FilterHolderTest
 */
public class FilterHolderTest
{

    @Test
    public void testInitialize()
        throws Exception
    {
        ServletHandler handler = new ServletHandler();

        final AtomicInteger counter = new AtomicInteger(0);
        Filter filter = new Filter()
        {
            @Override
            public void init(FilterConfig filterConfig) throws ServletException
            {
                counter.incrementAndGet();
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
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

        try (StacklessLogging stackless = new StacklessLogging(FilterHolder.class))
        {
            fh.initialize();
            fail("Not started");
        }
        catch (Exception e)
        {
            //expected
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
}
