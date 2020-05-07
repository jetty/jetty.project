//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.util.HashSet;
import java.util.Set;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletMapping;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.thread.Scheduler;

public class AsyncContextEvent extends AsyncEvent implements Runnable
{
    /**
     * Async dispatch attribute name prefix.
     */
    public static final String __ASYNC_PREFIX = "javax.servlet.async.";

    private final Context _context;
    private final AsyncContextState _asyncContext;
    private final HttpURI _baseURI;
    private volatile HttpChannelState _state;
    private ServletContext _dispatchContext;
    private String _dispatchPath;
    private volatile Scheduler.Task _timeoutTask;
    private Throwable _throwable;

    public AsyncContextEvent(Context context, AsyncContextState asyncContext, HttpChannelState state, Request baseRequest, ServletRequest request, ServletResponse response)
    {
        this (context, asyncContext, state, baseRequest, request, response, null);
    }

    public AsyncContextEvent(Context context, AsyncContextState asyncContext, HttpChannelState state, Request baseRequest, ServletRequest request, ServletResponse response, HttpURI baseURI)
    {
        super(null, request, response, null);
        _context = context;
        _asyncContext = asyncContext;
        _state = state;
        _baseURI = baseURI;

        // If we haven't been async dispatched before
        if (baseRequest.getAttribute(AsyncContext.ASYNC_REQUEST_URI) == null)
        {
            // We are setting these attributes during startAsync, when the spec implies that
            // they are only available after a call to AsyncContext.dispatch(...);
            Attributes oldAttributes = baseRequest.getAttributes();
            Attributes newAttributes;

            // have we been forwarded before?
            if (baseRequest.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI) != null)
                newAttributes = AsyncAttributes.fromForwardedAttributes(oldAttributes);
            else
                newAttributes = new AsyncAttributes(baseRequest, oldAttributes);

            baseRequest.setAttributes(newAttributes);
        }
    }

    public HttpURI getBaseURI()
    {
        return _baseURI;
    }

    public ServletContext getSuspendedContext()
    {
        return _context;
    }

    public Context getContext()
    {
        return _context;
    }

    public ServletContext getDispatchContext()
    {
        return _dispatchContext;
    }

    public ServletContext getServletContext()
    {
        return _dispatchContext == null ? _context : _dispatchContext;
    }

    public void setTimeoutTask(Scheduler.Task task)
    {
        _timeoutTask = task;
    }

    public boolean hasTimeoutTask()
    {
        return _timeoutTask != null;
    }

    public void cancelTimeoutTask()
    {
        Scheduler.Task task = _timeoutTask;
        _timeoutTask = null;
        if (task != null)
            task.cancel();
    }

    @Override
    public AsyncContext getAsyncContext()
    {
        return _asyncContext;
    }

    @Override
    public Throwable getThrowable()
    {
        return _throwable;
    }

    public void setDispatchContext(ServletContext context)
    {
        _dispatchContext = context;
    }

    /**
     * @return The path in the context (encoded with possible query string)
     */
    public String getDispatchPath()
    {
        return _dispatchPath;
    }

    /**
     * @param path encoded URI
     */
    public void setDispatchPath(String path)
    {
        _dispatchPath = path;
    }

    public void completed()
    {
        _timeoutTask = null;
        _asyncContext.reset();
    }

    public HttpChannelState getHttpChannelState()
    {
        return _state;
    }

    @Override
    public void run()
    {
        Scheduler.Task task = _timeoutTask;
        _timeoutTask = null;
        if (task != null)
            _state.timeout();
    }

    public void addThrowable(Throwable e)
    {
        if (_throwable == null)
            _throwable = e;
        else if (e != _throwable)
            _throwable.addSuppressed(e);
    }

    private static class AsyncAttributes extends Attributes.Wrapper
    {
        private String _requestURI;
        private String _contextPath;
        private String _servletPath;
        private String _pathInfo;
        private String _query;
        private HttpServletMapping _mapping;

        AsyncAttributes(Attributes attributes)
        {
            super(attributes);
        }

        AsyncAttributes(Request request, Attributes attributes)
        {
            super(attributes);
            _requestURI = request.getRequestURI();
            _contextPath = request.getContextPath();
            _servletPath = request.getServletPath();
            _pathInfo = request.getPathInfo();
            _query = request.getQueryString();
            _mapping = request.getHttpServletMapping();
        }

        static AsyncAttributes fromForwardedAttributes(Attributes attributes)
        {
            AsyncAttributes asyncAttributes = new AsyncAttributes(attributes);
            asyncAttributes._requestURI = (String)attributes.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
            asyncAttributes._contextPath = (String)attributes.getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH);
            asyncAttributes._servletPath = (String)attributes.getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH);
            asyncAttributes._pathInfo = (String)attributes.getAttribute(RequestDispatcher.FORWARD_PATH_INFO);
            asyncAttributes._query = (String)attributes.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING);
            asyncAttributes._mapping = (HttpServletMapping)attributes.getAttribute(RequestDispatcher.FORWARD_MAPPING);
            return asyncAttributes;
        }

        @Override
        public Object getAttribute(String key)
        {
            if (!key.startsWith(__ASYNC_PREFIX))
                return super.getAttribute(key);

            switch (key)
            {
                case AsyncContext.ASYNC_REQUEST_URI:
                    return _requestURI;
                case AsyncContext.ASYNC_CONTEXT_PATH:
                    return _contextPath;
                case AsyncContext.ASYNC_SERVLET_PATH:
                    return _servletPath;
                case AsyncContext.ASYNC_PATH_INFO:
                    return _pathInfo;
                case AsyncContext.ASYNC_QUERY_STRING:
                    return _query;
                case AsyncContext.ASYNC_MAPPING:
                    return _mapping;
                default:
                    return super.getAttribute(key);
            }
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            HashSet<String> set = new HashSet<>();
            for (String name : _attributes.getAttributeNameSet())
            {
                if (!name.startsWith(__ASYNC_PREFIX))
                    set.add(name);
            }

            set.add(AsyncContext.ASYNC_REQUEST_URI);
            set.add(AsyncContext.ASYNC_CONTEXT_PATH);
            set.add(AsyncContext.ASYNC_SERVLET_PATH);
            set.add(AsyncContext.ASYNC_PATH_INFO);
            set.add(AsyncContext.ASYNC_QUERY_STRING);
            set.add(AsyncContext.ASYNC_MAPPING);
            return set;
        }

        @Override
        public void setAttribute(String key, Object value)
        {
            if (!key.startsWith(__ASYNC_PREFIX))
                super.setAttribute(key, value);

            switch (key)
            {
                case AsyncContext.ASYNC_REQUEST_URI:
                    _requestURI = (String)value;
                    break;
                case AsyncContext.ASYNC_CONTEXT_PATH:
                    _contextPath = (String)value;
                    break;
                case AsyncContext.ASYNC_SERVLET_PATH:
                    _servletPath = (String)value;
                    break;
                case AsyncContext.ASYNC_PATH_INFO:
                    _pathInfo = (String)value;
                    break;
                case AsyncContext.ASYNC_QUERY_STRING:
                    _query = (String)value;
                    break;
                case AsyncContext.ASYNC_MAPPING:
                    _mapping = (HttpServletMapping)value;
                    break;
                default:
                    super.setAttribute(key, value);
            }
        }
    }
}
