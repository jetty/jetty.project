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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.URIUtil;

/**
 * Welcome Filter
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
 * @deprecated no replacement is offered, use standard Servlet web.xml welcome features
 */
@Deprecated
public class WelcomeFilter implements Filter
{
    private String welcome;

    @Override
    public void init(FilterConfig filterConfig)
    {
        welcome = filterConfig.getInitParameter("welcome");
        if (welcome == null)
            welcome = "index.html";
    }

    @Override
    public void doFilter(ServletRequest request,
                         ServletResponse response,
                         FilterChain chain)
        throws IOException, ServletException
    {
        String path = ((HttpServletRequest)request).getServletPath();
        if (welcome != null && path.endsWith("/"))
        {
            String uriInContext = URIUtil.encodePath(URIUtil.addPaths(path, welcome));
            request.getRequestDispatcher(uriInContext).forward(request, response);
        }
        else
            chain.doFilter(request, response);
    }

    @Override
    public void destroy()
    {
    }
}

