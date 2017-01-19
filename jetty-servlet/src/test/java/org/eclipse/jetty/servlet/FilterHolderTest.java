//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.Test;

/**
 * FilterHolderTest
 *
 *
 */
public class FilterHolderTest
{

    @Test
    public void testInitialize()
    throws Exception
    {
        ServletHandler handler = new ServletHandler();
        
        final AtomicInteger counter = new AtomicInteger(0);
        Filter filter = new Filter ()
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
