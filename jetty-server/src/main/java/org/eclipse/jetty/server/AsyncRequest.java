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

import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.continuation.ContinuationEvent;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.Timeout;

/* ------------------------------------------------------------ */
/** Asyncrhonous Request.
 * 
 */
public class AsyncRequest implements AsyncContext, Continuation
{
    // STATES:
    private static final int __IDLE=0;         // Idle request
    private static final int __DISPATCHED=1;   // Request dispatched to filter/servlet
    private static final int __SUSPENDING=2;   // Suspend called, but not yet returned to container
    private static final int __REDISPATCHING=3;// resumed while dispatched
    private static final int __SUSPENDED=4;    // Suspended and parked
    private static final int __UNSUSPENDING=5; // Has been scheduled
    private static final int __REDISPATCHED=6; // Request redispatched to filter/servlet
    private static final int __COMPLETING=7;   // complete while dispatched
    private static final int __UNCOMPLETED=8;  // Request is completable
    private static final int __COMPLETE=9;     // Request is complete
    
    // State table
    //                       __HANDLE      __UNHANDLE       __SUSPEND    __REDISPATCH   
    // IDLE */          {  __DISPATCHED,    __Illegal,      __Illegal,      __Illegal  },    
    // DISPATCHED */    {   __Illegal,  __UNCOMPLETED,   __SUSPENDING,       __Ignore  }, 
    // SUSPENDING */    {   __Illegal,    __SUSPENDED,      __Illegal,__REDISPATCHING  },
    // REDISPATCHING */ {   __Illegal,  _REDISPATCHED,      __Ignored,       __Ignore  },
    // COMPLETING */    {   __Illegal,  __UNCOMPLETED,      __Illegal,       __Illegal },
    // SUSPENDED */     {  __REDISPATCHED,  __Illegal,      __Illegal, __UNSUSPENDING  },
    // UNSUSPENDING */  {  __REDISPATCHED,  __Illegal,      __Illegal,       __Ignore  },
    // REDISPATCHED */  {   __Illegal,  __UNCOMPLETED,   __SUSPENDING,       __Ignore  },
    

    /* ------------------------------------------------------------ */
    protected HttpConnection _connection;
    private Object _listeners;

    /* ------------------------------------------------------------ */
    private int _state;
    private boolean _initial;
    private boolean _resumed;
    private boolean _expired;
    private boolean _keepWrappers;
    private long _timeoutMs;
    private AsyncEventState _event;
    
//    private StringBuilder _history = new StringBuilder();

    /* ------------------------------------------------------------ */
    protected AsyncRequest()
    {
        _state=__IDLE;
        _initial=true;
//        _history.append(super.toString());
//        _history.append('\n');
    }

    /* ------------------------------------------------------------ */
    protected void setConnection(final HttpConnection connection)
    {
        synchronized(this)
        {
            _connection=connection;
//            _history.append(connection.toString());
//            _history.append('\n');
        }
    }

    /* ------------------------------------------------------------ */
    public void addContinuationListener(ContinuationListener listener)
    {
        synchronized(this)
        {
            _listeners=LazyList.add(_listeners,listener);
//            _history.append('L');
        }
    }

    /* ------------------------------------------------------------ */
    public void setAsyncTimeout(long ms)
    {
        synchronized(this)
        {
            _timeoutMs=ms;
//            _history.append('T');
//            _history.append(ms);
        }
    } 

    /* ------------------------------------------------------------ */
    public long getAsyncTimeout()
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
    public void keepWrappers()
    {
        synchronized(this)
        {
//            _history.append('W');
            _keepWrappers=true;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#wrappersKept()
     */
    public boolean wrappersKept()
    {
        synchronized(this)
        {
            return _keepWrappers;
        }
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
                case __SUSPENDING:
                case __REDISPATCHING:
                case __COMPLETING:
                case __SUSPENDED:
                    return true;
                    
                default:
                    return false;   
            }
        }
    }

    /* ------------------------------------------------------------ */
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
                    (_state==__SUSPENDING)?"SUSPENDING":
                        (_state==__SUSPENDED)?"SUSPENDED":
                            (_state==__REDISPATCHING)?"REDISPATCHING":
                                (_state==__UNSUSPENDING)?"UNSUSPENDING":
                                    (_state==__REDISPATCHED)?"REDISPATCHED":
                                        (_state==__COMPLETING)?"COMPLETING":
                                            (_state==__UNCOMPLETED)?"UNCOMPLETED":
                                                (_state==__COMPLETE)?"COMPLETE":
                                                    ("UNKNOWN?"+_state))+
            (_initial?",initial":"")+
            (_resumed?",resumed":"")+
            (_expired?",expired":"");
        }
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see javax.servlet.ServletRequest#resume()
     */
    protected boolean handling()
    {
        synchronized (this)
        {
//            _history.append('H');
//            _history.append(_connection.getRequest().getUri().toString());
//            _history.append(':');
            _keepWrappers=false;
            
            switch(_state)
            {
                case __DISPATCHED:
                case __REDISPATCHED:
                case __COMPLETE:
                    throw new IllegalStateException(this.getStatusString());

                case __IDLE:
                    _initial=true;
                    _state=__DISPATCHED;
                    return true;

                case __SUSPENDING:
                case __REDISPATCHING:
                    throw new IllegalStateException(this.getStatusString());

                case __COMPLETING:
                    _state=__UNCOMPLETED;
                    return false;

                case __SUSPENDED:
                    return false;
                    
                case __UNSUSPENDING:
                    _state=__REDISPATCHED;
                    return true;

                default:
                    throw new IllegalStateException(""+_state);
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
//            _history.append('S');
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
                    _state=__SUSPENDING;
                    return;

                case __IDLE:
                    throw new IllegalStateException(this.getStatusString());

                case __SUSPENDING:
                case __REDISPATCHING:
                    return;

                case __COMPLETING:
                case __SUSPENDED:
                case __UNSUSPENDING:
                case __COMPLETE:
                    throw new IllegalStateException(this.getStatusString());

                default:
                    throw new IllegalStateException(""+_state);
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
//            _history.append('U');
            switch(_state)
            {
                case __REDISPATCHED:
                case __DISPATCHED:
                    _state=__UNCOMPLETED;
                    return true;

                case __IDLE:
                    throw new IllegalStateException(this.getStatusString());

                case __SUSPENDING:
                    _initial=false;
                    _state=__SUSPENDED;
                    scheduleTimeout(); // could block and change state.
                    if (_state==__SUSPENDED)
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

                case __SUSPENDED:
                case __UNSUSPENDING:
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
//            _history.append('D');
            switch(_state)
            {
                case __REDISPATCHED:
                case __DISPATCHED:
                case __IDLE:
                case __REDISPATCHING:
                case __COMPLETING:
                case __COMPLETE:
                case __UNCOMPLETED:
                    return;
                    
                case __SUSPENDING:
                    _state=__REDISPATCHING;
                    _resumed=true;
                    return;

                case __SUSPENDED:
                    dispatch=!_expired;
                    _state=__UNSUSPENDING;
                    _resumed=true;
                    break;
                    
                case __UNSUSPENDING:
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
        Object listeners=null;
        synchronized (this)
        {
//            _history.append('E');
            switch(_state)
            {
                case __SUSPENDING:
                case __SUSPENDED:
                    listeners=_listeners;
                    break;
                default:
                    return;
            }
            _expired=true;
        }
        
        if (listeners!=null)
        {
            for(int i=0;i<LazyList.size(listeners);i++)
            {
                try
                {
//                    synchronized (this)
//                    {
//                        _history.append('l');
//                        _history.append(i);
//                    }
                    ContinuationListener listener=((ContinuationListener)LazyList.get(listeners,i));
                    listener.onTimeout(_event);
                }
                catch(Exception e)
                {
                    Log.warn(e);
                }
            }
        }
        
        synchronized (this)
        {
//            _history.append('e');
            switch(_state)
            {
                case __SUSPENDING:
                case __SUSPENDED:
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
//            _history.append('C');
            switch(_state)
            {
                case __IDLE:
                case __COMPLETE:
                case __REDISPATCHING:
                case __COMPLETING:
                case __UNSUSPENDING:
                    return;
                    
                case __DISPATCHED:
                case __REDISPATCHED:
                    throw new IllegalStateException(this.getStatusString());

                case __SUSPENDING:
                    _state=__COMPLETING;
                    return;
                    
                case __SUSPENDED:
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
        Object listeners=null;
        synchronized (this)
        {
//            _history.append("c");
            switch(_state)
            {
                case __UNCOMPLETED:
                    _state=__COMPLETE;
                    listeners=_listeners;
                    break;
                    
                default:
                    throw new IllegalStateException(this.getStatusString());
            }
        }

        if (listeners!=null)
        {
            for(int i=0;i<LazyList.size(listeners);i++)
            {
                try
                {
//                    synchronized (this)
//                    {
//                        _history.append('l');
//                        _history.append(i);
//                    }
                    ((ContinuationListener)LazyList.get(listeners,i)).onComplete(_event);
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
            _keepWrappers=false;
            cancelTimeout();
            _timeoutMs=60000L; // TODO configure
            _listeners=null;
        }
    }    
    
    /* ------------------------------------------------------------ */
    public void cancel()
    {
        synchronized (this)
        {
//            _history.append("X");
            _state=__COMPLETE;
            _initial = false;
            cancelTimeout();
            _listeners=null;
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
                long expire_at = System.currentTimeMillis()+_timeoutMs;
                long wait=_timeoutMs;
                while (_timeoutMs>0 && wait>0)
                {
                    try
                    {
                        this.wait(wait);
                    }
                    catch (InterruptedException e)
                    {
                        Log.ignore(e);
                    }
                    wait=expire_at-System.currentTimeMillis();
                }

                if (_timeoutMs>0 && wait<=0)
                    expired();
            }            
        }
        else
            _connection.scheduleTimeout(_event._timeout,_timeoutMs);
    }

    /* ------------------------------------------------------------ */
    protected void cancelTimeout()
    {
        EndPoint endp=_connection.getEndPoint();
        if (endp.isBlocking())
        {
            synchronized(this)
            {
                _timeoutMs=0;
                this.notifyAll();
            }
        }
        else 
        {
            final AsyncEventState event=_event;
            if (event!=null)
                _connection.cancelTimeout(event._timeout);
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
            return _state==__COMPLETE;
        }
    }


    /* ------------------------------------------------------------ */
    public boolean isAsyncStarted()
    {
        synchronized (this)
        {
            switch(_state)
            {
                case __SUSPENDING:
                case __REDISPATCHING:
                case __UNSUSPENDING:
                case __SUSPENDED:
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
     * @see Continuation#suspend(long)
     */
    public void setTimeout(long timeoutMs)
    {
        setAsyncTimeout(timeoutMs);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see Continuation#suspend()
     */
    public void suspend()
    {
        // TODO simplify?
        AsyncRequest.this.suspend(_connection.getRequest().getServletContext(),_connection.getRequest(),_connection.getResponse());       
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#getServletRequest()
     */
    public ServletRequest getServletRequest()
    {
        return _connection.getRequest();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#getServletResponse()
     */
    public ServletResponse getServletResponse()
    {
        return _connection.getResponse();
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class AsyncEventState implements ContinuationEvent
    {
        private final ServletContext _suspendedContext;
        private final ServletRequest _request;
        private final ServletResponse _response;
        
        ServletContext _dispatchContext;
        
        String _path;
        final Timeout.Task _timeout = new Timeout.Task()
        {
            public void expired()
            {
                AsyncRequest.this.expired();
            }
        };
        
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
    }
    
    public String getHistory()
    {
//        synchronized (this)
//        {
//            return _history.toString();
//        }
        return null;
    }
}
