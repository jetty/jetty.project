//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;


import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.thread.Scheduler;

public class AsyncContextEvent extends AsyncEvent implements Runnable
{
    final private Context _context;
    final private AsyncContextState _asyncContext;
    private volatile HttpChannelState _state;
    private ServletContext _dispatchContext;
    private String _dispatchPath;
    private volatile Scheduler.Task _timeoutTask;
    private Throwable _throwable;

    public AsyncContextEvent(Context context,AsyncContextState asyncContext, HttpChannelState state, Request baseRequest, ServletRequest request, ServletResponse response)
    {
        super(null,request,response,null);
        _context=context;
        _asyncContext=asyncContext;
        _state=state;

        // If we haven't been async dispatched before
        if (baseRequest.getAttribute(AsyncContext.ASYNC_REQUEST_URI)==null)
        {
            // We are setting these attributes during startAsync, when the spec implies that
            // they are only available after a call to AsyncContext.dispatch(...);

            // have we been forwarded before?
            String uri=(String)baseRequest.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
            if (uri!=null)
            {
                baseRequest.setAttribute(AsyncContext.ASYNC_REQUEST_URI,uri);
                baseRequest.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH,baseRequest.getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH));
                baseRequest.setAttribute(AsyncContext.ASYNC_SERVLET_PATH,baseRequest.getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH));
                baseRequest.setAttribute(AsyncContext.ASYNC_PATH_INFO,baseRequest.getAttribute(RequestDispatcher.FORWARD_PATH_INFO));
                baseRequest.setAttribute(AsyncContext.ASYNC_QUERY_STRING,baseRequest.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING));
            }
            else
            {
                baseRequest.setAttribute(AsyncContext.ASYNC_REQUEST_URI,baseRequest.getRequestURI());
                baseRequest.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH,baseRequest.getContextPath());
                baseRequest.setAttribute(AsyncContext.ASYNC_SERVLET_PATH,baseRequest.getServletPath());
                baseRequest.setAttribute(AsyncContext.ASYNC_PATH_INFO,baseRequest.getPathInfo());
                baseRequest.setAttribute(AsyncContext.ASYNC_QUERY_STRING,baseRequest.getQueryString());
            }
        }
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
        return _dispatchContext==null?_context:_dispatchContext;
    }

    /**
     * @return The path in the context (encoded with possible query string)
     */
    public String getPath()
    {
        return _dispatchPath;
    }

    public void setTimeoutTask(Scheduler.Task task)
    {
        _timeoutTask = task;
    }

    public void cancelTimeoutTask()
    {
        Scheduler.Task task=_timeoutTask;
        _timeoutTask=null;
        if (task!=null)
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
        _dispatchContext=context;
    }

    /**
     * @param path encoded URI
     */
    public void setDispatchPath(String path)
    {
        _dispatchPath=path;
    }

    public void completed()
    {
        _timeoutTask=null;
        _asyncContext.reset();
    }

    public HttpChannelState getHttpChannelState()
    {
        return _state;
    }

    @Override
    public void run()
    {
        Scheduler.Task task=_timeoutTask;
        _timeoutTask=null;
        if (task!=null)
            _state.getHttpChannel().execute(() -> _state.onTimeout());
    }

    public void addThrowable(Throwable e)
    {
        if (_throwable==null)
            _throwable=e;
        else if (_throwable != e)
            _throwable.addSuppressed(e);
    }
}
