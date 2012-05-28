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
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.RequestDispatcher;
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
    //               handling()    suspend()     unhandle()    resume()       complete()  doComplete()
    //                             startAsync()                dispatch()   
    // IDLE          DISPATCHED                                               COMPLETING
    // DISPATCHED                  ASYNCSTARTED  UNCOMPLETED
    // ASYNCSTARTED                              ASYNCWAIT     REDISPATCHING  COMPLETING
    // REDISPATCHING                             REDISPATCHED  
    // ASYNCWAIT                                               REDISPATCH     COMPLETING
    // REDISPATCH    REDISPATCHED
    // REDISPATCHED                ASYNCSTARTED  UNCOMPLETED
    // COMPLETING    UNCOMPLETED                 UNCOMPLETED
    // UNCOMPLETED   UNCOMPLETED                                                          COMPLETED
    // COMPLETED

    public enum State 
    { 
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
        return _state;
    }
    
    /* ------------------------------------------------------------ */
    @Override
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
    @Override
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
    @Override
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
                case COMPLETING:
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
                    
                case COMPLETING:
                    _state=State.UNCOMPLETED;
                    return false;

                case UNCOMPLETED:
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
                    _state=State.UNCOMPLETED;
                    return true;

                case IDLE:
                    throw new IllegalStateException(this.getStatusString());

                case ASYNCSTARTED:
                    _initial=false;
                    _state=State.ASYNCWAIT;
                    scheduleTimeout(); 
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
    protected void doComplete()
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
        _channel.execute(_handleRequest);
    }

    /* ------------------------------------------------------------ */
    protected void scheduleTimeout()
    {
        Timer timer = _channel.getTimer();
        if (timer!=null)
            timer.schedule(_event._timeout,_timeoutMs);
    }

    /* ------------------------------------------------------------ */
    protected void cancelTimeout()
    {
        AsyncEventState event=_event;
        if (event!=null)
            event._timeout.cancel();
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
    @Override
    public void dispatch(ServletContext context, String path)
    {
        _event._dispatchContext=context;
        _event._path=path;
        dispatch();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void dispatch(String path)
    {
        _event._path=path;
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
    /**
     * @see Continuation#suspend()
     */
    @Override
    public void suspend(ServletResponse response)
    {
        _continuation=true;
        if (response instanceof ServletResponseWrapper)
        {
            _responseWrapped=true;
            HttpChannelState.this.suspend(_channel.getRequest().getServletContext(),_channel.getRequest(),response);       
        }
        else
        {
            _responseWrapped=false;
            HttpChannelState.this.suspend(_channel.getRequest().getServletContext(),_channel.getRequest(),_channel.getResponse());       
        }
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
        HttpChannelState.this.suspend(_channel.getRequest().getServletContext(),_channel.getRequest(),_channel.getResponse());       
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
    public class AsyncTimeout extends TimerTask
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
        private final TimerTask _timeout=  new AsyncTimeout();
        private final ServletContext _suspendedContext;
        private ServletContext _dispatchContext;
        private String _path;
        private Throwable _cause;
        
        public AsyncEventState(ServletContext context, ServletRequest request, ServletResponse response)
        {
            super(HttpChannelState.this, request,response);
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
    
    private final Runnable _handleRequest = new Runnable()
    {
        @Override
        public void run() 
        {
            _channel.process();
        }
    };
}
