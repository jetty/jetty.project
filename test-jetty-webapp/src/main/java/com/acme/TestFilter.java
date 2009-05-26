// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package com.acme;

import java.io.IOException;

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

/* ------------------------------------------------------------ */
/** TestFilter.
 * 
 *
 */
public class TestFilter implements Filter
{
    private ServletContext _context;
    
    /* ------------------------------------------------------------ */
    /* 
     * @see javax.servlet.Filter#init(javax.servlet.FilterConfig)
     */
    public void init(FilterConfig filterConfig) throws ServletException
    {
        _context= filterConfig.getServletContext();
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see javax.servlet.Filter#doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse, javax.servlet.FilterChain)
     */
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException
    {
        Integer old_value=null;
        ServletRequest r = request;
        while (r instanceof ServletRequestWrapper)
            r=((ServletRequestWrapper)r).getRequest();
        
        try
        {
            old_value=(Integer)request.getAttribute("testFilter");
            
            Integer value=(old_value==null)?new Integer(1):new Integer(old_value.intValue()+1);
                        
            request.setAttribute("testFilter", value);
            
            String qString = ((HttpServletRequest)request).getQueryString();
            if (qString != null && qString.indexOf("wrap")>=0)
            {
                request=new HttpServletRequestWrapper((HttpServletRequest)request);
            }
            _context.setAttribute("request"+r.hashCode(),value);
            
            chain.doFilter(request, response);
        }
        finally
        {
            request.setAttribute("testFilter", old_value);
            _context.setAttribute("request"+r.hashCode(),old_value);
        }
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see javax.servlet.Filter#destroy()
     */
    public void destroy()
    {
    }

}
