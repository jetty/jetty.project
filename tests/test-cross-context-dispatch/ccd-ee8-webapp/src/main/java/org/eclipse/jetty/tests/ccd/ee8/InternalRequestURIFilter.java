//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.ccd.ee8;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

/**
 * A servlet filter that will harshly change the return value of
 * {@link HttpServletRequest#getRequestURI()} to something that does
 * not satisfy the Servlet spec URI invariant {@code request URI == context path + servlet path + path info}
 */
public class InternalRequestURIFilter implements Filter
{
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        HttpServletRequest httpServletRequest = (HttpServletRequest)request;
        HttpServletResponse httpServletResponse = (HttpServletResponse)response;
        InternalRequestURIWrapper requestURIWrapper = new InternalRequestURIWrapper(httpServletRequest);
        chain.doFilter(requestURIWrapper, httpServletResponse);
    }

    private static class InternalRequestURIWrapper extends HttpServletRequestWrapper
    {
        public InternalRequestURIWrapper(HttpServletRequest request)
        {
            super(request);
        }

        @Override
        public String getRequestURI()
        {
            return "/internal/";
        }
    }
}
