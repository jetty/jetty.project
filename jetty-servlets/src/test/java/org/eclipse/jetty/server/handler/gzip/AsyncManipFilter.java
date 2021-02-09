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

package org.eclipse.jetty.server.handler.gzip;

import java.io.IOException;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Filter that merely manipulates the AsyncContext.
 * <p>
 * The pattern of manipulation is modeled after how DOSFilter behaves. The purpose of this filter is to test arbitrary filter chains that could see unintended
 * side-effects of async context manipulation.
 */
public class AsyncManipFilter implements Filter, AsyncListener
{
    private static final Logger LOG = Log.getLogger(AsyncManipFilter.class);
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
