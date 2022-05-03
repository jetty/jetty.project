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

package org.eclipse.jetty.ee10.servlets;

import java.io.IOException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter that merely manipulates the AsyncContext.
 * <p>
 * The pattern of manipulation is modeled after how DOSFilter behaves. The purpose of this filter is to test arbitrary filter chains that could see unintended
 * side-effects of async context manipulation.
 */
public class AsyncManipFilter implements Filter, AsyncListener
{
    private static final Logger LOG = LoggerFactory.getLogger(AsyncManipFilter.class);
    private static final String MANIP_KEY = AsyncManipFilter.class.getName();

    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        LOG.debug("doFilter() - {}", chain);
        AsyncContext ctx = (AsyncContext)request.getAttribute(MANIP_KEY);
        if (ctx == null)
        {
            LOG.debug("Initial pass through: {}", chain);
            ctx = request.startAsync();
            ctx.addListener(this);
            ctx.setTimeout(1000);
            LOG.debug("AsyncContext: {}", ctx);
            request.setAttribute(MANIP_KEY, ctx);
            return;
        }
        else
        {
            LOG.debug("Second pass through: {}", chain);
            chain.doFilter(request, response);
        }
    }

    @Override
    public void destroy()
    {
    }

    @Override
    public void onComplete(AsyncEvent event) throws IOException
    {
        LOG.debug("onComplete() {}", event);
    }

    @Override
    public void onTimeout(AsyncEvent event) throws IOException
    {
        LOG.debug("onTimeout() {}", event.getAsyncContext());
        event.getAsyncContext().dispatch();
    }

    @Override
    public void onError(AsyncEvent event) throws IOException
    {
        LOG.debug("onError()", event.getThrowable());
    }

    @Override
    public void onStartAsync(AsyncEvent event) throws IOException
    {
        LOG.debug("onTimeout() {}", event);
    }
}
