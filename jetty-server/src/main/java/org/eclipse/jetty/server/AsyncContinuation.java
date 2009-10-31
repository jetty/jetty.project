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

import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationThrowable;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.Timeout;

/* ------------------------------------------------------------ */
/** Implementation of Continuation and AsyncContext interfaces.
 * 
 */
public class AsyncContinuation implements AsyncContext, Continuation
{
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
    private static final int __IDLE=0;         // Idle request
    private static final int __DISPATCHED=1;   // Request dispatched to filter/servlet
    private static final int __ASYNCSTARTED=2; // Suspend called, but not yet returned to container
    private static final int __REDISPATCHING=3;// resumed while dispatched
    private static final int __ASYNCWAIT=4;    // Suspended and parked
    private static final int __REDISPATCH=5;   // Has been scheduled
    private static final int __REDISPATCHED=6; // Request redispatched to filter/servlet
    private static final int __COMPLETING=7;   // complete while dispatched
    private static final int __UNCOMPLETED=8;  // Request is completable
    private static final int __COMPLETED=9;    // Request is complete
    

    /* ------------------------------------------------------------ */
    protected HttpConnection _connection;
    private List<ContinuationListener> _continuationListeners;

    /* ------------------------------------------------------------ */
    private int _state;
    private boolean _initial;
    private boolean _resumed;
    private boolean _expired;
    private volatile boolean _responseWrapped;
    private long _timeoutMs=DEFAULT_TIMEOUT;
    private AsyncEventState _event;
    private volatile long _expireAt;
    
    /* ------------------------------------------------------------ */
    protected AsyncContinuation()
    {
        _state=__IDLE;
        _initial=true;
    }

    /* ------------------------------------------------------------ */
    protected void setConnection(final HttpConnection connection)
    {
        synchronized(this)
        {
            _connection=connection;
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
                case __ASYNCSTARTED:
                case __REDISPATCHING:
                case __COMPLETING:
                case __ASYNCWAIT:
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
            return
            ((_state==__IDLE)?"IDLE":
                (_state==__DISPATCHED)?"DISPATCHED":
                    (_state==__ASYNCSTARTED)?"ASYNCSTARTED":
                        (_state==__ASYNCWAIT)?"ASYNCWAIT":
                            (_state==__REDISPATCHING)?"REDISPATCHING":
                                (_state==__REDISPATCH)?"REDISPATCH":
                                    (_state==__REDISPATCHED)?"REDISPATCHED":
                                        (_state==__COMPLETING)?"COMPLETING":
                                            (_state==__UNCOMPLETED)?"UNCOMPLETED":
                                                (_state==__COMPLETED)?"COMPLETE":
                                                    ("UNKNOWN?"+_state))+
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
            _responseWrapped=false;
            
            switch(_state)
            {
                case __IDLE:
                    _initial=true;
                    _state=__DISPATCHED;
                    return true;
                    
                case __COMPLETING:
                    _state=__UNCOMPLETED;
                    return false;

                case __ASYNCWAIT:
                    return false;
                    
                case __REDISPATCH:
                    _state=__REDISPATCHED;
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
            _resumed=false;
            _expired=false;
            
            if (_event==null || request!=_event.getRequest() || response != _event.getResponse() || context != _event.getServletContext())  
                _event=new AsyncEventState(context,request,response);
            else
            {
                _event._dispatchContext=null;
                _event._path=null;
            }
            
            switch(_state)
            {
                case __DISPATCHED:
                case __REDISPATCHED:
                    _state=__ASYNCSTARTED;
                    break;

                default:
                    throw new IllegalStateException(this.getStatusString());
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
            List<ContinuationListener> listeners=_continuationListeners;
            
            switch(_state)
            {
                case __REDISPATCHED:
                case __DISPATCHED:
                    _state=__UNCOMPLETED;
                    return true;

                case __IDLE:
                    throw new IllegalStateException(this.getStatusString());

                case __ASYNCSTARTED:
                    _initial=false;
                    _state=__ASYNCWAIT;
                    scheduleTimeout(); // could block and change state.
                    if (_state==__ASYNCWAIT)
                        return true;
                    else if (_state==__COMPLETING)
                    {
                        _state=__UNCOMPLETED;
                        return true;
                    }
                    _initial=false;
                    _state=__REDISPATCHED;
                    return false; 

                case __REDISPATCHING:
                    _initial=false;
                    _state=__REDISPATCHED;
                    return false; 

                case __COMPLETING:
                    _initial=false;
                    _state=__UNCOMPLETED;
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
                case __ASYNCSTARTED:
                    _state=__REDISPATCHING;
                    _resumed=true;
                    return;

                case __ASYNCWAIT:
                    dispatch=!_expired;
                    _state=__REDISPATCH;
                    _resumed=true;
                    break;
                    
                case __REDISPATCH:
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
        final List<ContinuationListener> listeners;
        synchronized (this)
        {
            switch(_state)
            {
                case __ASYNCSTARTED:
                case __ASYNCWAIT:
                    listeners=_continuationListeners;
                    break;
                default:
                    listeners=null;
                    return;
            }
            _expired=true;
        }
        
        if (listeners!=null)
        {
            for (int i=0;i<listeners.size();i++)
            {
                ContinuationListener listener=listeners.get(i);
                try
                {
                    listener.onTimeout(this);
                }
                catch(Exception e)
                {
                    Log.warn(e);
                }
            }
        }
        
        synchronized (this)
        {
            switch(_state)
            {
                case __ASYNCSTARTED:
                case __ASYNCWAIT:
                    dispatch();
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
                case __DISPATCHED:
                case __REDISPATCHED:
                    throw new IllegalStateException(this.getStatusString());

                case __ASYNCSTARTED:
                    _state=__COMPLETING;
                    return;
                    
                case __ASYNCWAIT:
                    _state=__COMPLETING;
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
    /* (non-Javadoc)
     * @see javax.servlet.ServletRequest#complete()
     */
    protected void doComplete()
    {
        final List<ContinuationListener> listeners;
        synchronized (this)
        {
            switch(_state)
            {
                case __UNCOMPLETED:
                    _state=__COMPLETED;
                    listeners=_continuationListeners;
                    break;
                    
                default:
                    listeners=null;
                    throw new IllegalStateException(this.getStatusString());
            }
        }
        
        if (listeners!=null)
        {
            for(int i=0;i<listeners.size();i++)
            {
                try
                {
                    listeners.get(i).onComplete(this);
                }
                catch(Exception e)
                {
                    Log.warn(e);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected void recycle()
    {
        synchronized (this)
        {
//            _history.append("r\n");
            switch(_state)
            {
                case __DISPATCHED:
                case __REDISPATCHED:
                    throw new IllegalStateException(getStatusString());
                default:
                    _state=__IDLE;
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
        EndPoint endp=_connection.getEndPoint();
        if (!endp.isBlocking())
        {
            ((AsyncEndPoint)endp).dispatch();
        }
    }

    /* ------------------------------------------------------------ */
    protected void scheduleTimeout()
    {
        EndPoint endp=_connection.getEndPoint();
        if (endp.isBlocking())
        {
            synchronized(this)
            {
                _expireAt = System.currentTimeMillis()+_timeoutMs;
                long wait=_timeoutMs;
                while (_expireAt>0 && wait>0)
                {
                    try
                    {
                        this.wait(wait);
                    }
                    catch (InterruptedException e)
                    {
                        Log.ignore(e);
                    }
                    wait=_expireAt-System.currentTimeMillis();
                }

                if (_expireAt>0 && wait<=0)
                {
                    expired();
                }
            }            
        }
        else
            _connection.scheduleTimeout(_event,_timeoutMs);
    }

    /* ------------------------------------------------------------ */
    protected void cancelTimeout()
    {
        EndPoint endp=_connection.getEndPoint();
        if (endp.isBlocking())
        {
            synchronized(this)
            {
                _expireAt=0;
                this.notifyAll();
            }
        }
        else 
        {
            final AsyncEventState event=_event;
            if (event!=null)
                _connection.cancelTimeout(event);
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isCompleting()
    {
        synchronized (this)
        {
            return _state==__COMPLETING;
        }
    }
    
    /* ------------------------------------------------------------ */
    boolean isUncompleted()
    {
        synchronized (this)
        {
            return _state==__UNCOMPLETED;
        }
    } 
    
    /* ------------------------------------------------------------ */
    public boolean isComplete()
    {
        synchronized (this)
        {
            return _state==__COMPLETED;
        }
    }


    /* ------------------------------------------------------------ */
    public boolean isAsyncStarted()
    {
        synchronized (this)
        {
            switch(_state)
            {
                case __ASYNCSTARTED:
                case __REDISPATCHING:
                case __REDISPATCH:
                case __ASYNCWAIT:
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
                case __IDLE:
                case __DISPATCHED:
                case __UNCOMPLETED:
                case __COMPLETED:
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
            return _event.getRequest();
        return _connection.getRequest();
    }

    /* ------------------------------------------------------------ */
    public ServletResponse getResponse()
    {
        if (_event!=null)
            return _event.getResponse();
        return _connection.getResponse();
    }

    /* ------------------------------------------------------------ */
    public void start(Runnable run)
    {
        final AsyncEventState event=_event;
        if (event!=null)
            ((Context)event.getServletContext()).getContextHandler().handle(run);
    }

    /* ------------------------------------------------------------ */
    public boolean hasOriginalRequestAndResponse()
    {
        synchronized (this)
        {
            return (_event!=null && _event.getRequest()==_connection._request && _event.getResponse()==_connection._response);
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
        _responseWrapped=!(response instanceof Response);
        AsyncContinuation.this.suspend(_connection.getRequest().getServletContext(),_connection.getRequest(),response); 
    }

    /* ------------------------------------------------------------ */
    /**
     * @see Continuation#suspend()
     */
    public void suspend()
    {
        _responseWrapped=false;
        AsyncContinuation.this.suspend(_connection.getRequest().getServletContext(),_connection.getRequest(),_connection.getResponse());       
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#getServletResponse()
     */
    public ServletResponse getServletResponse()
    {
        if (_responseWrapped && _event!=null && _event.getResponse()!=null)
            return _event.getResponse();
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
            if (Log.isDebugEnabled())
                throw new ContinuationThrowable();
            else
                throw __exception;
        }
        throw new IllegalStateException("!suspended");
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class AsyncEventState extends Timeout.Task
    {
        private final ServletContext _suspendedContext;
        private final ServletRequest _request;
        private final ServletResponse _response;
        private ServletContext _dispatchContext;
        private String _path;
        
        public AsyncEventState(ServletContext context, ServletRequest request, ServletResponse response)
        {
            _suspendedContext=context;
            _request=request;
            _response=response;
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

        public ServletRequest getRequest()
        {
            return _request;
        }

        public ServletResponse getResponse()
        {
            return _response;
        }
        
        public String getPath()
        {
            return _path;
        }

        @Override
        public void expired()
        {
            AsyncContinuation.this.expired();
        }
    }
}
