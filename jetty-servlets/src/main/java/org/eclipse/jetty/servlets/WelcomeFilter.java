//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlets;
import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

/* ------------------------------------------------------------ */
/** Welcome Filter
 * This filter can be used to server an index file for a directory 
 * when no index file actually exists (thus the web.xml mechanism does
 * not work).
 * 
 * This filter will dispatch requests to a directory (URLs ending with /)
 * to the welcome URL determined by the "welcome" init parameter.  So if
 * the filter "welcome" init parameter is set to "index.do" then a request
 * to "/some/directory/" will be dispatched to "/some/directory/index.do" and
 * will be handled by any servlets mapped to that URL.
 *
 * Requests to "/some/directory" will be redirected to "/some/directory/".
 */
public  class WelcomeFilter implements Filter
{
    private String welcome;
    
    public void init(FilterConfig filterConfig)
    {
        welcome=filterConfig.getInitParameter("welcome");
	if (welcome==null)
	    welcome="index.html";
    }

    /* ------------------------------------------------------------ */
    public void doFilter(ServletRequest request,
                         ServletResponse response,
                         FilterChain chain)
	throws IOException, ServletException
    {
        String path=((HttpServletRequest)request).getServletPath();
        if (welcome!=null && path.endsWith("/"))
            request.getRequestDispatcher(path+welcome).forward(request,response);
        else
            chain.doFilter(request, response);
    }

    public void destroy() {}
}

