// ========================================================================
// Copyright (c) 2007-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationThrowable;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout;

/* ------------------------------------------------------------ */
/** Implementation of Continuation and AsyncContext interfaces
 * 
 */
public class AsyncContinuation implements AsyncContext, Continuation
{
    private static final Logger LOG = Log.getLogger(AsyncContinuation.class);

    private final static long DEFAULT_TIMEOUT=30000L;
    
    private final static ContinuationThrowable __exception = new ContinuationThrowable();
    
    // STATES:
    //               handling()    suspend()     unhandle()    resume()       complete()  doComplete()
    //                             startAsync()                dispatch()   
    // IDLE          DISPATCHED      
    // DISPATCHED                  ASYNCSTARTED  UNCOMPLETED
    // ASYNCSTARTED                              ASYNCWAIT     REDISPATCHING  COMPLETING
    // REDISPATCHING                             REDISPATCHED  
    // ASYNCWAIT                                               REDISPATCH     COMPLETING
    // REDISPATCH    REDISPATCHED
    // REDISPATCHED                ASYNCSTARTED  UNCOMPLETED
    // COMPLETING    UNCOMPLETED                 UNCOMPLETED
    // UNCOMPLETED                                                                        COMPLETED
    // COMPLETED

    public enum State { 
        IDLE,         // Idle request
        DISPATCHED,   // Request dispatched to filter/servlet
        ASYNCSTARTED, // Suspend called, but not yet returned to container
        REDISPATCHING,// resumed while dispatched
        ASYNCWAIT,    // Suspended and parked
        REDISPATCH,   // Has been scheduled
        REDISPATCHED, // Request redispatched to filter/servlet
        COMPLETING,   // complete while dispatched
        UNCOMPLETED,  // Request is completable
        COMPLETED     // Request is complete
    };
    
    /* ------------------------------------------------------------ */
    protected HttpProcessor _connection;
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
    private volatile long _expireAt;    
    private volatile boolean _continuation;
    
    /* ------------------------------------------------------------ */
    protected AsyncContinuation()
    {
        _state=State.IDLE;
        _initial=true;
    }

    /* ------------------------------------------------------------ */
    protected void setConnection(final HttpProcessor connection)
    {
        synchronized(this)
        {
            _connection=connection;
        }
    }

    /* ------------------------------------------------------------ */
    public void addListener(AsyncListener listener)
    {
        synchronized(this)
        {
            if (_asyncListeners==null)
                _asyncListeners=new ArrayList<AsyncListener>();
            _asyncListeners.add(listener);
        }
    }

    /* ------------------------------------------------------------ */
    public void addListener(AsyncListener listener,ServletRequest request, ServletResponse response)
    {
        synchronized(this)
        {
            // TODO handle the request/response ???
            if (_asyncListeners==null)
                _asyncListeners=new ArrayList<AsyncListener>();
            _asyncListeners.add(listener);
        }
    }

    /* ------------------------------------------------------------ */
    public void addContinuationListener(ContinuationListener listener)
    {
        synchronized(this)
        {
            if (_continuationListeners==null)
                _continuationListeners=new ArrayList<ContinuationListener>();
            _continuationListeners.add(listener);
        }
    }

    /* ------------------------------------------------------------ */
    public void setTimeout(long ms)
    {
        synchronized(this)
        {
            _timeoutMs=ms;
        }
    } 

    /* ------------------------------------------------------------ */
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
     * @see org.eclipse.jetty.continuation.Continuation#keepWrappers()
     */

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#isResponseWrapped()
     */
    public boolean isResponseWrapped()
    {
        return _responseWrapped;
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see javax.servlet.ServletRequest#isInitial()
     */
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
    public boolean isSuspended()
    {
        synchronized(this)
        {
            switch(_state)
            {
                case ASYNCSTARTED:
                case REDISPATCHING:
                case COMPLETING:
                case ASYNCWAIT:
                    return true;
                    
                default:
                    return false;   
            }
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
                case COMPLETING:
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
     * @return false if the handling of the request should not proceed
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
                    
                case COMPLETING:
                    _state=State.UNCOMPLETED;
                    return false;

                case ASYNCWAIT:
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
    protected void suspend(final ServletContext context,
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
                        _event._path=null;
                    }

                    _state=State.ASYNCSTARTED;
                    List<AsyncListener> recycle=_lastAsyncListeners;
                    _lastAsyncListeners=_asyncListeners;
                    _asyncListeners=recycle;
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
                    _state=State.UNCOMPLETED;
                    return true;

                case IDLE:
                    throw new IllegalStateException(this.getStatusString());

                case ASYNCSTARTED:
                    _initial=false;
                    _state=State.ASYNCWAIT;
                    scheduleTimeout(); // could block and change state.
                    if (_state==State.ASYNCWAIT)
                        return true;
                    else if (_state==State.COMPLETING)
                    {
                        _state=State.UNCOMPLETED;
                        return true;
                    }         
                    _initial=false;
                    _state=State.REDISPATCHED;
                    return false; 

                case REDISPATCHING:
                    _initial=false;
                    _state=State.REDISPATCHED;
                    return false; 

                case COMPLETING:
                    _initial=false;
                    _state=State.UNCOMPLETED;
                    return true;

                default:
                    throw new IllegalStateException(this.getStatusString());
            }
        }
    }

    /* ------------------------------------------------------------ */
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

                case ASYNCSTARTED:
                    _state=State.COMPLETING;
                    return;
                    
                case ASYNCWAIT:
                    _state=State.COMPLETING;
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
    protected void doComplete(Throwable ex)
    {
        final List<ContinuationListener> cListeners;
        final List<AsyncListener> aListeners;
        synchronized (this)
        {
            switch(_state)
            {
                case UNCOMPLETED:
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
                    if (ex!=null)
                    {
                        _event.getSuppliedRequest().setAttribute(Dispatcher.ERROR_EXCEPTION,ex);
                        _event.getSuppliedRequest().setAttribute(Dispatcher.ERROR_MESSAGE,ex.getMessage());
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
        _connection.asyncDispatch();
    }

    /* ------------------------------------------------------------ */
    protected void scheduleTimeout()
    {
        _connection.scheduleTimeout(_event._timeout,_timeoutMs);
    }

    /* ------------------------------------------------------------ */
    protected void cancelTimeout()
    {
        _connection.cancelTimeout(_event._timeout);
    }

    /* ------------------------------------------------------------ */
    public boolean isCompleting()
    {
        synchronized (this)
        {
            return _state==State.COMPLETING;
        }
    }
    
    /* ------------------------------------------------------------ */
    boolean isUncompleted()
    {
        synchronized (this)
        {
            return _state==State.UNCOMPLETED;
        }
    } 
    
    /* ------------------------------------------------------------ */
    public boolean isComplete()
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
                case UNCOMPLETED:
                case COMPLETED:
                    return false;

                default:
                    return true;
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void dispatch(ServletContext context, String path)
    {
        _event._dispatchContext=context;
        _event._path=path;
        dispatch();
    }

    /* ------------------------------------------------------------ */
    public void dispatch(String path)
    {
        _event._path=path;
        dispatch();
    }

    /* ------------------------------------------------------------ */
    public Request getBaseRequest()
    {
        return _connection.getRequest();
    }
    
    /* ------------------------------------------------------------ */
    public ServletRequest getRequest()
    {
        if (_event!=null)
            return _event.getSuppliedRequest();
        return _connection.getRequest();
    }

    /* ------------------------------------------------------------ */
    public ServletResponse getResponse()
    {
        if (_responseWrapped && _event!=null && _event.getSuppliedResponse()!=null)
            return _event.getSuppliedResponse();
        return _connection.getResponse();
    }

    /* ------------------------------------------------------------ */
    public void start(final Runnable run)
    {
        final AsyncEventState event=_event;
        if (event!=null)
        {
            _connection.getServer().getThreadPool().dispatch(new Runnable()
            {
                public void run()
                {
                    ((Context)event.getServletContext()).getContextHandler().handle(run);
                }
            });
        }
    }

    /* ------------------------------------------------------------ */
    public boolean hasOriginalRequestAndResponse()
    {
        synchronized (this)
        {
            return (_event!=null && _event.getSuppliedRequest()==_connection.getRequest() && _event.getSuppliedResponse()==_connection.getResponse());
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
    public void resume()
    {
        dispatch();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see Continuation#suspend()
     */
    public void suspend(ServletResponse response)
    {
        _continuation=true;
        if (response instanceof ServletResponseWrapper)
        {
            _responseWrapped=true;
            AsyncContinuation.this.suspend(_connection.getRequest().getServletContext(),_connection.getRequest(),response);       
        }
        else
        {
            _responseWrapped=false;
            AsyncContinuation.this.suspend(_connection.getRequest().getServletContext(),_connection.getRequest(),_connection.getResponse());       
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see Continuation#suspend()
     */
    public void suspend()
    {
        _responseWrapped=false;
        _continuation=true;
        AsyncContinuation.this.suspend(_connection.getRequest().getServletContext(),_connection.getRequest(),_connection.getResponse());       
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#getServletResponse()
     */
    public ServletResponse getServletResponse()
    {
        if (_responseWrapped && _event!=null && _event.getSuppliedResponse()!=null)
            return _event.getSuppliedResponse();
        return _connection.getResponse();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#getAttribute(java.lang.String)
     */
    public Object getAttribute(String name)
    {
        return _connection.getRequest().getAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#removeAttribute(java.lang.String)
     */
    public void removeAttribute(String name)
    {
        _connection.getRequest().removeAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#setAttribute(java.lang.String, java.lang.Object)
     */
    public void setAttribute(String name, Object attribute)
    {
        _connection.getRequest().setAttribute(name,attribute);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#undispatch()
     */
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
    public class AsyncTimeout extends Timeout.Task implements Runnable
    {
            @Override
            public void expired()
            {
                AsyncContinuation.this.expired();
            }

            @Override
            public void run()
            {
                AsyncContinuation.this.expired();
            }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class AsyncEventState extends AsyncEvent
    {
        private final ServletContext _suspendedContext;
        private ServletContext _dispatchContext;
        private String _path;
        private Timeout.Task _timeout=  new AsyncTimeout();
        
        public AsyncEventState(ServletContext context, ServletRequest request, ServletResponse response)
        {
            super(AsyncContinuation.this, request,response);
            _suspendedContext=context;
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
        
        public String getPath()
        {
            return _path;
        }
    }
}
