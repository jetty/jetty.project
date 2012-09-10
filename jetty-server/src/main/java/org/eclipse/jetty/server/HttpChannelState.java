//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationThrowable;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

/* ------------------------------------------------------------ */
/** Implementation of Continuation and AsyncContext interfaces
 *
 */
public class HttpChannelState implements AsyncContext, Continuation
{
    private static final Logger LOG = Log.getLogger(HttpChannelState.class);

    private final static long DEFAULT_TIMEOUT=30000L;

    private final static ContinuationThrowable __exception = new ContinuationThrowable();

    // STATES:
    //                handling()    suspend()     unhandle()    resume()       complete()     completed()
    //                              startAsync()                dispatch()
    // IDLE           DISPATCHED                                               COMPLETECALLED
    // DISPATCHED                   ASYNCSTARTED  COMPLETING
    // ASYNCSTARTED                               ASYNCWAIT     REDISPATCHING  COMPLETECALLED
    // REDISPATCHING                              REDISPATCHED
    // ASYNCWAIT                                                REDISPATCH     COMPLETECALLED
    // REDISPATCH     REDISPATCHED
    // REDISPATCHED                 ASYNCSTARTED  COMPLETING
    // COMPLETECALLED COMPLETING                  COMPLETING
    // COMPLETING     COMPLETING                                                              COMPLETED
    // COMPLETED

    public enum State
    {
        IDLE,          // Idle request
        DISPATCHED,    // Request dispatched to filter/servlet
        ASYNCSTARTED,  // Suspend called, but not yet returned to container
        REDISPATCHING, // resumed while dispatched
        ASYNCWAIT,     // Suspended and parked
        REDISPATCH,    // Has been scheduled
        REDISPATCHED,  // Request redispatched to filter/servlet
        COMPLETECALLED,// complete called
        COMPLETING,    // Request is completable
        COMPLETED      // Request is complete
    }

    /* ------------------------------------------------------------ */
    private final HttpChannel _channel;
    private List<AsyncListener> _lastAsyncListeners;
    private List<AsyncListener> _asyncListeners;
    private List<ContinuationListener> _continuationListeners;

    /* ------------------------------------------------------------ */
    private State _state;
    private boolean _initial;
    private boolean _resumed;
    private boolean _expired;
    private volatile boolean _responseWrapped;
    private long _timeoutMs=DEFAULT_TIMEOUT;
    private AsyncEventState _event;
    private volatile boolean _continuation;

    /* ------------------------------------------------------------ */
    protected HttpChannelState(HttpChannel channel)
    {
        _channel=channel;
        _state=State.IDLE;
        _initial=true;
    }

    /* ------------------------------------------------------------ */
    public State getState()
    {
        synchronized(this)
        {
            return _state;
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void addListener(AsyncListener listener)
    {
        synchronized(this)
        {
            if (_asyncListeners==null)
                _asyncListeners=new ArrayList<>();
            _asyncListeners.add(listener);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void addListener(AsyncListener listener,ServletRequest request, ServletResponse response)
    {
        synchronized(this)
        {
            // TODO handle the request/response ???
            if (_asyncListeners==null)
                _asyncListeners=new ArrayList<>();
            _asyncListeners.add(listener);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void addContinuationListener(ContinuationListener listener)
    {
        synchronized(this)
        {
            if (_continuationListeners==null)
                _continuationListeners=new ArrayList<>();
            _continuationListeners.add(listener);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setTimeout(long ms)
    {
        synchronized(this)
        {
            _timeoutMs=ms;
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public long getTimeout()
    {
        synchronized(this)
        {
            return _timeoutMs;
        }
    }

    /* ------------------------------------------------------------ */
    public AsyncEventState getAsyncEventState()
    {
        synchronized(this)
        {
            return _event;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#isResponseWrapped()
     */
    @Override
    public boolean isResponseWrapped()
    {
        return _responseWrapped;
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see javax.servlet.ServletRequest#isInitial()
     */
    @Override
    public boolean isInitial()
    {
        synchronized(this)
        {
            return _initial;
        }
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see javax.servlet.ServletRequest#isSuspended()
     */
    @Override
    public boolean isSuspended()
    {
        synchronized(this)
        {
            switch(_state)
            {
                case ASYNCSTARTED:
                case REDISPATCHING:
                case COMPLETECALLED:
                case ASYNCWAIT:
                    return true;

                default:
                    return false;
            }
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        synchronized(this)
        {
            return _state==State.IDLE;
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isSuspending()
    {
        synchronized(this)
        {
            switch(_state)
            {
                case ASYNCSTARTED:
                case ASYNCWAIT:
                    return true;

                default:
                    return false;
            }
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isDispatchable()
    {
        synchronized(this)
        {
            switch(_state)
            {
                case REDISPATCH:
                case REDISPATCHED:
                case REDISPATCHING:
                case COMPLETECALLED:
                    return true;

                default:
                    return false;
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        synchronized (this)
        {
            return super.toString()+"@"+getStatusString();
        }
    }

    /* ------------------------------------------------------------ */
    public String getStatusString()
    {
        synchronized (this)
        {
            return _state+
            (_initial?",initial":"")+
            (_resumed?",resumed":"")+
            (_expired?",expired":"");
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if the handling of the request should proceed
     */
    protected boolean handling()
    {
        synchronized (this)
        {
            _continuation=false;
            _responseWrapped=false;

            switch(_state)
            {
                case IDLE:
                    _initial=true;
                    _state=State.DISPATCHED;
                    if (_lastAsyncListeners!=null)
                        _lastAsyncListeners.clear();
                    if (_asyncListeners!=null)
                        _asyncListeners.clear();
                    else
                    {
                        _asyncListeners=_lastAsyncListeners;
                        _lastAsyncListeners=null;
                    }
                    return true;

                case COMPLETECALLED:
                    _state=State.COMPLETING;
                    return false;

                case ASYNCWAIT:
                case COMPLETING:
                case COMPLETED:
                    return false;

                case REDISPATCH:
                    _state=State.REDISPATCHED;
                    return true;

                default:
                    throw new IllegalStateException(this.getStatusString());
            }
        }
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see javax.servlet.ServletRequest#suspend(long)
     */
    private void doSuspend(final ServletContext context,
            final ServletRequest request,
            final ServletResponse response)
    {
        synchronized (this)
        {
            switch(_state)
            {
                case DISPATCHED:
                case REDISPATCHED:
                    _resumed=false;
                    _expired=false;

                    if (_event==null || request!=_event.getSuppliedRequest() || response != _event.getSuppliedResponse() || context != _event.getServletContext())
                        _event=new AsyncEventState(context,request,response);
                    else
                    {
                        _event._dispatchContext=null;
                        _event._pathInContext=null;
                    }
                    _state=State.ASYNCSTARTED;
                    List<AsyncListener> listeners=_lastAsyncListeners;
                    _lastAsyncListeners=_asyncListeners;
                    _asyncListeners=listeners;
                    if (_asyncListeners!=null)
                        _asyncListeners.clear();
                    break;

                default:
                    throw new IllegalStateException(this.getStatusString());
            }
        }

        if (_lastAsyncListeners!=null)
        {
            for (AsyncListener listener : _lastAsyncListeners)
            {
                try
                {
                    listener.onStartAsync(_event);
                }
                catch(Exception e)
                {
                    LOG.warn(e);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected void error(Throwable th)
    {
        synchronized (this)
        {
            // TODO should we change state here?
            if (_event!=null)
                _event._cause=th;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Signal that the HttpConnection has finished handling the request.
     * For blocking connectors, this call may block if the request has
     * been suspended (startAsync called).
     * @return true if handling is complete, false if the request should
     * be handled again (eg because of a resume that happened before unhandle was called)
     */
    protected boolean unhandle()
    {
        synchronized (this)
        {
            switch(_state)
            {
                case REDISPATCHED:
                case DISPATCHED:
                    _state=State.COMPLETING;
                    return true;

                case IDLE:
                    throw new IllegalStateException(this.getStatusString());

                case ASYNCSTARTED:
                    _initial=false;
                    _state=State.ASYNCWAIT;
                    scheduleTimeout();
                    if (_state==State.ASYNCWAIT)
                        return true;
                    else if (_state==State.COMPLETECALLED)
                    {
                        _state=State.COMPLETING;
                        return true;
                    }
                    _initial=false;
                    _state=State.REDISPATCHED;
                    return false;

                case REDISPATCHING:
                    _initial=false;
                    _state=State.REDISPATCHED;
                    return false;

                case COMPLETECALLED:
                    _initial=false;
                    _state=State.COMPLETING;
                    return true;

                default:
                    throw new IllegalStateException(this.getStatusString());
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void dispatch()
    {
        boolean dispatch=false;
        synchronized (this)
        {
            switch(_state)
            {
                case ASYNCSTARTED:
                    _state=State.REDISPATCHING;
                    _resumed=true;
                    return;

                case ASYNCWAIT:
                    dispatch=!_expired;
                    _state=State.REDISPATCH;
                    _resumed=true;
                    break;

                case REDISPATCH:
                    return;

                default:
                    throw new IllegalStateException(this.getStatusString());
            }
        }

        if (dispatch)
        {
            cancelTimeout();
            scheduleDispatch();
        }
    }

    /* ------------------------------------------------------------ */
    protected void expired()
    {
        final List<ContinuationListener> cListeners;
        final List<AsyncListener> aListeners;
        synchronized (this)
        {
            switch(_state)
            {
                case ASYNCSTARTED:
                case ASYNCWAIT:
                    cListeners=_continuationListeners;
                    aListeners=_asyncListeners;
                    break;
                default:
                    cListeners=null;
                    aListeners=null;
                    return;
            }
            _expired=true;
        }

        if (aListeners!=null)
        {
            for (AsyncListener listener : aListeners)
            {
                try
                {
                    listener.onTimeout(_event);
                }
                catch(Exception e)
                {
                    LOG.warn(e);
                }
            }
        }
        if (cListeners!=null)
        {
            for (ContinuationListener listener : cListeners)
            {
                try
                {
                    listener.onTimeout(this);
                }
                catch(Exception e)
                {
                    LOG.warn(e);
                }
            }
        }



        synchronized (this)
        {
            switch(_state)
            {
                case ASYNCSTARTED:
                case ASYNCWAIT:
                    if (_continuation)
                        dispatch();
                   else
                        // TODO maybe error dispatch?
                        complete();
            }
        }

        scheduleDispatch();
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see javax.servlet.ServletRequest#complete()
     */
    @Override
    public void complete()
    {
        // just like resume, except don't set _resumed=true;
        boolean dispatch=false;
        synchronized (this)
        {
            switch(_state)
            {
                case DISPATCHED:
                case REDISPATCHED:
                    throw new IllegalStateException(this.getStatusString());

                case IDLE:
                case ASYNCSTARTED:
                    _state=State.COMPLETECALLED;
                    return;

                case ASYNCWAIT:
                    _state=State.COMPLETECALLED;
                    dispatch=!_expired;
                    break;

                default:
                    throw new IllegalStateException(this.getStatusString());
            }
        }

        if (dispatch)
        {
            cancelTimeout();
            scheduleDispatch();
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public <T extends AsyncListener> T createListener(Class<T> clazz) throws ServletException
    {
        try
        {
            // TODO inject
            return clazz.newInstance();
        }
        catch(Exception e)
        {
            throw new ServletException(e);
        }
    }


    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see javax.servlet.ServletRequest#complete()
     */
    protected void completed()
    {
        final List<ContinuationListener> cListeners;
        final List<AsyncListener> aListeners;
        synchronized (this)
        {
            switch(_state)
            {
                case COMPLETING:
                    _state=State.COMPLETED;
                    cListeners=_continuationListeners;
                    aListeners=_asyncListeners;
                    break;

                default:
                    cListeners=null;
                    aListeners=null;
                    throw new IllegalStateException(this.getStatusString());
            }
        }

        if (aListeners!=null)
        {
            for (AsyncListener listener : aListeners)
            {
                try
                {
                    if (_event!=null && _event._cause!=null)
                    {
                        _event.getSuppliedRequest().setAttribute(RequestDispatcher.ERROR_EXCEPTION,_event._cause);
                        _event.getSuppliedRequest().setAttribute(RequestDispatcher.ERROR_MESSAGE,_event._cause.getMessage());
                        listener.onError(_event);
                    }
                    else
                        listener.onComplete(_event);
                }
                catch(Exception e)
                {
                    LOG.warn(e);
                }
            }
        }
        if (cListeners!=null)
        {
            for (ContinuationListener listener : cListeners)
            {
                try
                {
                    listener.onComplete(this);
                }
                catch(Exception e)
                {
                    LOG.warn(e);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected void recycle()
    {
        synchronized (this)
        {
            switch(_state)
            {
                case DISPATCHED:
                case REDISPATCHED:
                    throw new IllegalStateException(getStatusString());
                default:
                    _state=State.IDLE;
            }
            _initial = true;
            _resumed=false;
            _expired=false;
            _responseWrapped=false;
            cancelTimeout();
            _timeoutMs=DEFAULT_TIMEOUT;
            _continuationListeners=null;
            if (_event!=null)
                _event._cause=null;
        }
    }

    /* ------------------------------------------------------------ */
    public void cancel()
    {
        synchronized (this)
        {
            cancelTimeout();
            _continuationListeners=null;
        }
    }

    /* ------------------------------------------------------------ */
    protected void scheduleDispatch()
    {
        _channel.execute(_channel);
    }

    /* ------------------------------------------------------------ */
    protected void scheduleTimeout()
    {
        Scheduler scheduler = _channel.getScheduler();
        if (scheduler!=null)
            _event._timeout=scheduler.schedule(new AsyncTimeout(),_timeoutMs,TimeUnit.MILLISECONDS);
    }

    /* ------------------------------------------------------------ */
    protected void cancelTimeout()
    {
        AsyncEventState event=_event;
        if (event!=null)
        {
            Scheduler.Task task=event._timeout;
            if (task!=null)
                task.cancel();
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isCompleteCalled()
    {
        synchronized (this)
        {
            return _state==State.COMPLETECALLED;
        }
    }

    /* ------------------------------------------------------------ */
    boolean isCompleting()
    {
        synchronized (this)
        {
            return _state==State.COMPLETING;
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isCompleted()
    {
        synchronized (this)
        {
            return _state==State.COMPLETED;
        }
    }


    /* ------------------------------------------------------------ */
    public boolean isAsyncStarted()
    {
        synchronized (this)
        {
            switch(_state)
            {
                case ASYNCSTARTED:
                case REDISPATCHING:
                case REDISPATCH:
                case ASYNCWAIT:
                    return true;

                default:
                    return false;
            }
        }
    }


    /* ------------------------------------------------------------ */
    public boolean isAsync()
    {
        synchronized (this)
        {
            switch(_state)
            {
                case IDLE:
                case DISPATCHED:
                case COMPLETING:
                case COMPLETED:
                    return false;

                default:
                    return true;
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void dispatch(ServletContext context, String path)
    {
        _event._dispatchContext=context;
        _event._pathInContext=path;
        dispatch();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void dispatch(String path)
    {
        _event._pathInContext=path;
        dispatch();
    }

    /* ------------------------------------------------------------ */
    public Request getBaseRequest()
    {
        return _channel.getRequest();
    }

    /* ------------------------------------------------------------ */
    @Override
    public ServletRequest getRequest()
    {
        if (_event!=null)
            return _event.getSuppliedRequest();
        return _channel.getRequest();
    }

    /* ------------------------------------------------------------ */
    @Override
    public ServletResponse getResponse()
    {
        if (_responseWrapped && _event!=null && _event.getSuppliedResponse()!=null)
            return _event.getSuppliedResponse();
        return _channel.getResponse();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void start(final Runnable run)
    {
        final AsyncEventState event=_event;
        if (event!=null)
        {
            _channel.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    ((Context)event.getServletContext()).getContextHandler().handle(run);
                }
            });
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean hasOriginalRequestAndResponse()
    {
        synchronized (this)
        {
            return (_event!=null && _event.getSuppliedRequest()==_channel.getRequest() && _event.getSuppliedResponse()==_channel.getResponse());
        }
    }

    /* ------------------------------------------------------------ */
    public ContextHandler getContextHandler()
    {
        final AsyncEventState event=_event;
        if (event!=null)
            return ((Context)event.getServletContext()).getContextHandler();
        return null;
    }


    /* ------------------------------------------------------------ */
    /**
     * @see Continuation#isResumed()
     */
    @Override
    public boolean isResumed()
    {
        synchronized (this)
        {
            return _resumed;
        }
    }
    /* ------------------------------------------------------------ */
    /**
     * @see Continuation#isExpired()
     */
    @Override
    public boolean isExpired()
    {
        synchronized (this)
        {
            return _expired;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see Continuation#resume()
     */
    @Override
    public void resume()
    {
        dispatch();
    }



    /* ------------------------------------------------------------ */
    protected void suspend(final ServletContext context,
            final ServletRequest request,
            final ServletResponse response)
    {
        synchronized (this)
        {
            _responseWrapped=!(response instanceof Response);
            doSuspend(context,request,response);
            if (request instanceof HttpServletRequest)
            {
                _event._pathInContext = URIUtil.addPaths(((HttpServletRequest)request).getServletPath(),((HttpServletRequest)request).getPathInfo());
            }
        }
    }


    /* ------------------------------------------------------------ */
    /**
     * @see Continuation#suspend()
     */
    @Override
    public void suspend(ServletResponse response)
    {
        _continuation=true;
        _responseWrapped=!(response instanceof Response);
        doSuspend(_channel.getRequest().getServletContext(),_channel.getRequest(),response);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see Continuation#suspend()
     */
    @Override
    public void suspend()
    {
        _responseWrapped=false;
        _continuation=true;
        doSuspend(_channel.getRequest().getServletContext(),_channel.getRequest(),_channel.getResponse());
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#getServletResponse()
     */
    @Override
    public ServletResponse getServletResponse()
    {
        if (_responseWrapped && _event!=null && _event.getSuppliedResponse()!=null)
            return _event.getSuppliedResponse();
        return _channel.getResponse();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#getAttribute(java.lang.String)
     */
    @Override
    public Object getAttribute(String name)
    {
        return _channel.getRequest().getAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#removeAttribute(java.lang.String)
     */
    @Override
    public void removeAttribute(String name)
    {
        _channel.getRequest().removeAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#setAttribute(java.lang.String, java.lang.Object)
     */
    @Override
    public void setAttribute(String name, Object attribute)
    {
        _channel.getRequest().setAttribute(name,attribute);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#undispatch()
     */
    @Override
    public void undispatch()
    {
        if (isSuspended())
        {
            if (LOG.isDebugEnabled())
                throw new ContinuationThrowable();
            else
                throw __exception;
        }
        throw new IllegalStateException("!suspended");
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class AsyncTimeout implements Runnable
    {
        @Override
        public void run()
        {
            HttpChannelState.this.expired();
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class AsyncEventState extends AsyncEvent
    {
        private Scheduler.Task _timeout;
        private final ServletContext _suspendedContext;
        private ServletContext _dispatchContext;
        private String _pathInContext;
        private Throwable _cause;

        public AsyncEventState(ServletContext context, ServletRequest request, ServletResponse response)
        {
            super(HttpChannelState.this, request,response);
            _suspendedContext=context;
            // Get the base request So we can remember the initial paths
            Request r=_channel.getRequest();

            // If we haven't been async dispatched before
            if (r.getAttribute(AsyncContext.ASYNC_REQUEST_URI)==null)
            {
                // We are setting these attributes during startAsync, when the spec implies that
                // they are only available after a call to AsyncContext.dispatch(...);

                // have we been forwarded before?
                String uri=(String)r.getAttribute(Dispatcher.FORWARD_REQUEST_URI);
                if (uri!=null)
                {
                    r.setAttribute(AsyncContext.ASYNC_REQUEST_URI,uri);
                    r.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH,r.getAttribute(Dispatcher.FORWARD_CONTEXT_PATH));
                    r.setAttribute(AsyncContext.ASYNC_SERVLET_PATH,r.getAttribute(Dispatcher.FORWARD_SERVLET_PATH));
                    r.setAttribute(AsyncContext.ASYNC_PATH_INFO,r.getAttribute(Dispatcher.FORWARD_PATH_INFO));
                    r.setAttribute(AsyncContext.ASYNC_QUERY_STRING,r.getAttribute(Dispatcher.FORWARD_QUERY_STRING));
                }
                else
                {
                    r.setAttribute(AsyncContext.ASYNC_REQUEST_URI,r.getRequestURI());
                    r.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH,r.getContextPath());
                    r.setAttribute(AsyncContext.ASYNC_SERVLET_PATH,r.getServletPath());
                    r.setAttribute(AsyncContext.ASYNC_PATH_INFO,r.getPathInfo());
                    r.setAttribute(AsyncContext.ASYNC_QUERY_STRING,r.getQueryString());
                }
            }
        }

        public ServletContext getSuspendedContext()
        {
            return _suspendedContext;
        }

        public ServletContext getDispatchContext()
        {
            return _dispatchContext;
        }

        public ServletContext getServletContext()
        {
            return _dispatchContext==null?_suspendedContext:_dispatchContext;
        }

        /* ------------------------------------------------------------ */
        /**
         * @return The path in the context
         */
        public String getPath()
        {
            return _pathInContext;
        }
    }
}
