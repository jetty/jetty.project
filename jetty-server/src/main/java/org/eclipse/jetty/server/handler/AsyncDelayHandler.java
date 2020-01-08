//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;

/**
 * A handler wrapper that provides the framework to asynchronously
 * delay the handling of a request.  While it uses standard servlet
 * API for asynchronous servlets, it adjusts the dispatch type of the
 * request so that it does not appear to be asynchronous during the
 * delayed dispatch.
 */
public class AsyncDelayHandler extends HandlerWrapper
{
    public static final String AHW_ATTR = "o.e.j.s.h.AsyncHandlerWrapper";

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (!isStarted() || _handler == null)
            return;

        // Get the dispatcher types
        DispatcherType ctype = baseRequest.getDispatcherType();
        DispatcherType dtype = (DispatcherType)baseRequest.getAttribute(AHW_ATTR);
        Object asyncContextPath = null;
        Object asyncPathInfo = null;
        Object asyncQueryString = null;
        Object asyncRequestUri = null;
        Object asyncServletPath = null;

        // Is this request a restarted one?
        boolean restart = false;
        if (dtype != null)
        {
            // fake the dispatch type to the original
            baseRequest.setAttribute(AHW_ATTR, null);
            baseRequest.setDispatcherType(dtype);
            restart = true;

            asyncContextPath = baseRequest.getAttribute(AsyncContext.ASYNC_CONTEXT_PATH);
            baseRequest.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH, null);
            asyncPathInfo = baseRequest.getAttribute(AsyncContext.ASYNC_PATH_INFO);
            baseRequest.setAttribute(AsyncContext.ASYNC_PATH_INFO, null);
            asyncQueryString = baseRequest.getAttribute(AsyncContext.ASYNC_QUERY_STRING);
            baseRequest.setAttribute(AsyncContext.ASYNC_QUERY_STRING, null);
            asyncRequestUri = baseRequest.getAttribute(AsyncContext.ASYNC_REQUEST_URI);
            baseRequest.setAttribute(AsyncContext.ASYNC_REQUEST_URI, null);
            asyncServletPath = baseRequest.getAttribute(AsyncContext.ASYNC_SERVLET_PATH);
            baseRequest.setAttribute(AsyncContext.ASYNC_SERVLET_PATH, null);
        }

        // Should we handle this request now?
        if (!startHandling(baseRequest, restart))
        {
            // No, so go async and remember dispatch type
            AsyncContext context = baseRequest.startAsync();
            baseRequest.setAttribute(AHW_ATTR, ctype);

            delayHandling(baseRequest, context);
            return;
        }

        // Handle the request
        try
        {
            _handler.handle(target, baseRequest, request, response);
        }
        finally
        {
            if (restart)
            {
                // reset the request
                baseRequest.setDispatcherType(ctype);
                baseRequest.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH, asyncContextPath);
                baseRequest.setAttribute(AsyncContext.ASYNC_PATH_INFO, asyncPathInfo);
                baseRequest.setAttribute(AsyncContext.ASYNC_QUERY_STRING, asyncQueryString);
                baseRequest.setAttribute(AsyncContext.ASYNC_REQUEST_URI, asyncRequestUri);
                baseRequest.setAttribute(AsyncContext.ASYNC_SERVLET_PATH, asyncServletPath);
            }

            // signal the request is leaving the handler
            endHandling(baseRequest);
        }
    }

    /**
     * Called to indicate that a request has been presented for handling
     *
     * @param request The request to handle
     * @param restart True if this request is being restarted after a delay
     * @return True if the request should be handled now
     */
    protected boolean startHandling(Request request, boolean restart)
    {
        return true;
    }

    /**
     * Called to indicate that a requests handling is being delayed/
     * The implementation should arrange for context.dispatch() to be
     * called when the request should be handled.  It may also set
     * timeouts on the context.
     *
     * @param request The request to be delayed
     * @param context The AsyncContext of the delayed request
     */
    protected void delayHandling(Request request, AsyncContext context)
    {
        context.dispatch();
    }

    /**
     * Called to indicated the handling of the request is ending.
     * This is only the end of the current dispatch of the request and
     * if the request is asynchronous, it may be handled again.
     *
     * @param request The request
     */
    protected void endHandling(Request request)
    {

    }
}
