// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.annotation;

import java.io.IOException;
import java.lang.reflect.Method;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class PojoFilter implements Filter, PojoWrapper
{
    private Object _pojo;
    private Method _doFilterMethod;
    private static final Class[] __params = new Class[] {HttpServletRequest.class, HttpServletResponse.class, FilterChain.class};
    
    public PojoFilter (Object pojo)
    {
        if (pojo == null)
            throw new IllegalArgumentException ("Pojo is null");
        
        _pojo=pojo;
        
        try
        {
            _doFilterMethod = _pojo.getClass().getDeclaredMethod("doFilter", __params);
        }
        catch (Exception e)
        {
            throw new IllegalStateException (e);
        }

    }
    
    public Object getPojo()
    {
        return _pojo;
    }
    
    public void destroy()
    {
       //TODO???? Should try to find a destroy method on the pojo?
    }

    
    public void doFilter(ServletRequest req, ServletResponse resp,
            FilterChain chain) throws IOException, ServletException
    {   
        try
        {
            _doFilterMethod.invoke(_pojo, new Object[]{req, resp, chain});
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }

    public void init(FilterConfig arg0) throws ServletException
    {
        // TODO ???? Should try to find an init() method on the pojo?
    }


}
