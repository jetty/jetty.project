//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
