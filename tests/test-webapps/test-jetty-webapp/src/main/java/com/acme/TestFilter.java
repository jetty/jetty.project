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

package com.acme;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * TestFilter.
 *
 * This filter checks for a none local request, and if the init parameter
 * "remote" is not set to true, then all non local requests are forwarded
 * to /remote.html
 */
public class TestFilter implements Filter
{
    private static final Logger LOG = Log.getLogger(TestFilter.class);

    private boolean _remote;
    private ServletContext _context;
    private final Set<String> _allowed = new HashSet<String>();

    /*
     * @see javax.servlet.Filter#init(javax.servlet.FilterConfig)
     */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        _context = filterConfig.getServletContext();
        _remote = Boolean.parseBoolean(filterConfig.getInitParameter("remote"));
        _allowed.add("/favicon.ico");
        _allowed.add("/jetty_banner.gif");
        _allowed.add("/remote.html");

        LOG.debug("TestFilter#remote=" + _remote);
    }

    /*
     * @see javax.servlet.Filter#doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse, javax.servlet.FilterChain)
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException
    {
        String from = request.getRemoteAddr();
        String to = request.getLocalAddr();
        String path = ((HttpServletRequest)request).getServletPath();

        if (!_remote && !_allowed.contains(path) && !from.equals(to))
        {
            _context.getRequestDispatcher("/remote.html").forward(request, response);
            return;
        }

        Integer oldValue = null;
        ServletRequest r = request;
        while (r instanceof ServletRequestWrapper)
        {
            r = ((ServletRequestWrapper)r).getRequest();
        }

        try
        {
            oldValue = (Integer)request.getAttribute("testFilter");

            Integer value = (oldValue == null) ? new Integer(1) : new Integer(oldValue.intValue() + 1);

            request.setAttribute("testFilter", value);

            String qString = ((HttpServletRequest)request).getQueryString();
            if (qString != null && qString.indexOf("wrap") >= 0)
            {
                request = new HttpServletRequestWrapper((HttpServletRequest)request);
            }
            _context.setAttribute("request" + r.hashCode(), value);

            chain.doFilter(request, response);
        }
        finally
        {
            request.setAttribute("testFilter", oldValue);
            _context.setAttribute("request" + r.hashCode(), oldValue);
        }
    }

    /*
     * @see javax.servlet.Filter#destroy()
     */
    @Override
    public void destroy()
    {
    }
}
