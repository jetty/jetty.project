//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.TimeoutException;

import javax.servlet.AsyncListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * Implementation of AsyncContext interface that holds the state of request-response cycle.
 */
public class HttpChannelState
{
    private static final Logger LOG = Log.getLogger(HttpChannelState.class);

    private final static long DEFAULT_TIMEOUT=Long.getLong("org.eclipse.jetty.server.HttpChannelState.DEFAULT_TIMEOUT",30000L);

    /**
     * The dispatched state of the HttpChannel, used to control the overall lifecycle
     */
    public enum State
    {
        IDLE,             // Idle request
        DISPATCHED,       // Request dispatched to filter/servlet
        ASYNC_WAIT,       // Suspended and waiting
        ASYNC_WOKEN,      // Dispatch to handle from ASYNC_WAIT
        ASYNC_IO,         // Dispatched for async IO
        COMPLETING,       // Response is completable
        COMPLETED,        // Response is completed
        UPGRADED          // Request upgraded the connection
    }

    /**
     * The actions to take as the channel moves from state to state.
     */
    public enum Action
    {
        DISPATCH,         // handle a normal request dispatch
        ASYNC_DISPATCH,   // handle an async request dispatch
        ERROR_DISPATCH,   // handle a normal error
        ASYNC_ERROR,      // handle an async error
        WRITE_CALLBACK,   // handle an IO write callback
        READ_CALLBACK,    // handle an IO read callback
        COMPLETE,         // Complete the response
        TERMINATED,       // No further actions
        WAIT,             // Wait for further events
    }

    /**
     * The state of the servlet async API.  This can lead or follow the
     * channel dispatch state and also includes reasons such as expired,
     * dispatched or completed.
     */
    public enum Async
    {
        NOT_ASYNC,
        STARTED,          // AsyncContext.startAsync() has been called
        DISPATCH,         //
        COMPLETE,         // AsyncContext.complete() has been called
        EXPIRING,         // AsyncContext timeout just happened
        EXPIRED,          // AsyncContext timeout has been processed
        ERRORING,         // An error just happened
        ERRORED           // The error has been processed
    }

    public enum Interest
    {
        NONE(false),
        NEEDED(true),
        REGISTERED(true);
        
        final boolean _interested;
        boolean isInterested() { return _interested;}
        
        Interest(boolean interest)
        {
            _interested = interest;
        }
    }
    
    private final boolean DEBUG=LOG.isDebugEnabled();
    private final Locker _locker=new Locker();
    private final HttpChannel _channel;

    private List<AsyncListener> _asyncListeners;
    private State _state;
    private Async _async;
    private boolean _initial;
    private boolean _asyncReadPossible;
    private Interest _asyncRead=Interest.NONE;
    private boolean _asyncWritePossible;
    private long _timeoutMs=DEFAULT_TIMEOUT;
    private AsyncContextEvent _event;

    protected HttpChannelState(HttpChannel channel)
    {
        _channel=channel;
        _state=State.IDLE;
        _async=Async.NOT_ASYNC;
        _initial=true;
    }

    public State getState()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return _state;
        }
    }

    public void addListener(AsyncListener listener)
    {
        try(Locker.Lock lock= _locker.lock())
        {
            if (_asyncListeners==null)
                _asyncListeners=new ArrayList<>();
            _asyncListeners.add(listener);
        }
    }

    public void setTimeout(long ms)
    {
        try(Locker.Lock lock= _locker.lock())
        {
            _timeoutMs=ms;
        }
    }

    public long getTimeout()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return _timeoutMs;
        }
    }

    public AsyncContextEvent getAsyncContextEvent()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return _event;
        }
    }

    @Override
    public String toString()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return String.format("%s@%x{s=%s a=%s i=%b r=%s/%s w=%b}",
                getClass().getSimpleName(),
                hashCode(),
                _state,
                _async,
                _initial,
                _asyncRead,
                _asyncReadPossible,
                _asyncWritePossible);
        }
    }

    private String getStatusStringLocked()
    {
        return String.format("s=%s i=%b a=%s",_state,_initial,_async);
    }

    public String getStatusString()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return getStatusStringLocked();
        }
    }

    /**
     * @return Next handling of the request should proceed
     */
    protected Action handling()
    {
        if(DEBUG)
            LOG.debug("{} handling {}",this,_state);
        try(Locker.Lock lock= _locker.lock())
        {
            switch(_state)
            {
                case IDLE:
                    _initial=true;
                    _state=State.DISPATCHED;
                    return Action.DISPATCH;

                case COMPLETING:
                case COMPLETED:
                    return Action.TERMINATED;

                case ASYNC_WOKEN:
                    if (_asyncRead.isInterested() && _asyncReadPossible)
                    {
                        _state=State.ASYNC_IO;
                        _asyncRead=Interest.NONE;
                        return Action.READ_CALLBACK;
                    }

                    if (_asyncWritePossible)
                    {
                        _state=State.ASYNC_IO;
                        _asyncWritePossible=false;
                        return Action.WRITE_CALLBACK;
                    }

                    switch(_async)
                    {
                        case COMPLETE:
                            _state=State.COMPLETING;
                            return Action.COMPLETE;
                        case DISPATCH:
                            _state=State.DISPATCHED;
                            _async=Async.NOT_ASYNC;
                            return Action.ASYNC_DISPATCH;
                        case EXPIRING:
                            break;
                        case EXPIRED:
                            _state=State.DISPATCHED;
                            _async=Async.NOT_ASYNC;
                            return Action.ERROR_DISPATCH;
                        case STARTED:
                            return Action.WAIT;
                        case ERRORING:
                            _state=State.DISPATCHED;
                            return Action.ASYNC_ERROR;
                        case NOT_ASYNC:
                            break;
                        default:
                            throw new IllegalStateException(getStatusStringLocked());
                    }

                    return Action.WAIT;

                case ASYNC_IO:
                case ASYNC_WAIT:
                case DISPATCHED:
                case UPGRADED:
                default:
                    throw new IllegalStateException(getStatusStringLocked());

            }
        }
    }

    public void startAsync(AsyncContextEvent event)
    {
        final List<AsyncListener> lastAsyncListeners;

        try(Locker.Lock lock= _locker.lock())
        {
            if (_state!=State.DISPATCHED || _async!=Async.NOT_ASYNC)
                throw new IllegalStateException(this.getStatusStringLocked());

            _async=Async.STARTED;
            _event=event;
            lastAsyncListeners=_asyncListeners;
            _asyncListeners=null;            
        }

        if (lastAsyncListeners!=null)
        {
            Runnable callback=new Runnable()
            {
                @Override
                public void run()
                {
                    for (AsyncListener listener : lastAsyncListeners)
                    {
                        try
                        {
                            listener.onStartAsync(event);
                        }
                        catch(Throwable e)
                        {
                            // TODO Async Dispatch Error
                            LOG.warn(e);
                        }
                    }
                }
                @Override
                public String toString()
                {
                    return "startAsync";
                }
            };
                  
            runInContext(event,callback);
        }
    }

    protected void error(Throwable th)
    {
        try(Locker.Lock lock= _locker.lock())
        {
            if (_event!=null)
                _event.addThrowable(th);
            _async=Async.ERRORING;
        }
    }

    public void asyncError(Throwable failure)
    {
        AsyncContextEvent event = null;
        try (Locker.Lock lock= _locker.lock())
        {
            switch (_state)
            {
                case IDLE:
                case DISPATCHED:
                case COMPLETING:
                case COMPLETED:
                case UPGRADED:
                case ASYNC_IO:
                case ASYNC_WOKEN:
                {
                    break;
                }
                case ASYNC_WAIT:
                {
                    _event.addThrowable(failure);
                    _state=State.ASYNC_WOKEN;
                    _async=Async.ERRORING;
                    event=_event;
                    break;
                }
                default:
                {
                    throw new IllegalStateException(getStatusStringLocked());
                }
            }
        }

        if (event != null)
        {
            cancelTimeout(event);
            runInContext(event, _channel);
        }
    }

    /**
     * Signal that the HttpConnection has finished handling the request.
     * For blocking connectors, this call may block if the request has
     * been suspended (startAsync called).
     * @return next actions
     * be handled again (eg because of a resume that happened before unhandle was called)
     */
    protected Action unhandle()
    {
        Action action;
        boolean read_interested=false;

        if(DEBUG)
            LOG.debug("{} unhandle {}",this,_state);

        try(Locker.Lock lock= _locker.lock())
        {
            switch(_state)
            {
                case COMPLETING:
                case COMPLETED:
                    return Action.TERMINATED;

                case DISPATCHED:
                case ASYNC_IO:
                    break;

                default:
                    throw new IllegalStateException(this.getStatusStringLocked());
            }

            _initial=false;
            switch(_async)
            {
                case COMPLETE:
                    _state=State.COMPLETING;
                    _async=Async.NOT_ASYNC;
                    action=Action.COMPLETE;
                    break;

                case DISPATCH:
                    _state=State.DISPATCHED;
                    _async=Async.NOT_ASYNC;
                    action=Action.ASYNC_DISPATCH;
                    break;

                case EXPIRED:
                    _state=State.DISPATCHED;
                    _async=Async.NOT_ASYNC;
                    action=Action.ERROR_DISPATCH;
                    break;

                case STARTED:
                    if (_asyncRead.isInterested() && _asyncReadPossible)
                    {
                        _state=State.ASYNC_IO;
                        _asyncRead=Interest.NONE;
                        action=Action.READ_CALLBACK;
                    }
                    else if (_asyncWritePossible)
                    {
                        _state=State.ASYNC_IO;
                        _asyncWritePossible=false;
                        action=Action.WRITE_CALLBACK;
                    }
                    else
                    {
                        _state=State.ASYNC_WAIT;
                        action=Action.WAIT; 
                        if (_asyncRead==Interest.NEEDED)
                        {
                            _asyncRead=Interest.REGISTERED;
                            read_interested=true;
                        }
                        Scheduler scheduler=_channel.getScheduler();
                        if (scheduler!=null && _timeoutMs>0)
                            _event.setTimeoutTask(scheduler.schedule(_event,_timeoutMs,TimeUnit.MILLISECONDS));
                    }
                    break;

                case EXPIRING:
                    _state=State.ASYNC_WAIT;
                    action=Action.WAIT;
                    break;

                case ERRORING:
                    _state=State.DISPATCHED;
                    action=Action.ASYNC_ERROR;
                    break;

                case ERRORED:
                    _state=State.DISPATCHED;
                    action=Action.ERROR_DISPATCH;
                    _async=Async.NOT_ASYNC;
                    break;

                case NOT_ASYNC:
                    _state=State.COMPLETING;
                    action=Action.COMPLETE;
                    break;

                default:
                    _state=State.COMPLETING;
                    action=Action.COMPLETE;
                    break;
            }
        }

        if (read_interested)
            _channel.onAsyncWaitForContent();

        return action;
    }

    public void dispatch(ServletContext context, String path)
    {
        boolean dispatch=false;
        AsyncContextEvent event=null;
        try(Locker.Lock lock= _locker.lock())
        {
            boolean started=false;
            event=_event;
            switch(_async)
            {
                case STARTED:
                    started=true;
                    break;
                case EXPIRING:
                case ERRORED:
                    break;
                default:
                    throw new IllegalStateException(this.getStatusStringLocked());
            }
            _async=Async.DISPATCH;

            if (context!=null)
                _event.setDispatchContext(context);
            if (path!=null)
                _event.setDispatchPath(path);

            if (started)
            {
                switch(_state)
                {
                    case DISPATCHED:
                    case ASYNC_IO:
                    case ASYNC_WOKEN:
                        break;
                    case ASYNC_WAIT:
                        _state=State.ASYNC_WOKEN;
                        dispatch=true;
                        break;
                    default:
                        LOG.warn("async dispatched when complete {}",this);
                        break;
                }
            }
        }

        cancelTimeout(event);
        if (dispatch)
            scheduleDispatch();
    }

    protected void onTimeout()
    {
        final List<AsyncListener> listeners;
        AsyncContextEvent event;
        try(Locker.Lock lock= _locker.lock())
        {
            if (_async!=Async.STARTED)
                return;
            _async=Async.EXPIRING;
            event=_event;
            listeners=_asyncListeners;

        }

        if (LOG.isDebugEnabled())
            LOG.debug("Async timeout {}",this);

        if (listeners!=null)
        {
            Runnable callback=new Runnable()
            {
                @Override
                public void run()
                {
                    for (AsyncListener listener : listeners)
                    {
                        try
                        {
                            listener.onTimeout(event);
                        }
                        catch(Throwable e)
                        {
                            LOG.debug(e);
                            event.addThrowable(e);
                            _channel.getRequest().setAttribute(RequestDispatcher.ERROR_EXCEPTION,event.getThrowable());
                            break;
                        }
                    }
                }
                @Override
                public String toString()
                {
                    return "onTimeout";
                }
            };
            
            runInContext(event,callback);
        }

        boolean dispatch=false;
        try(Locker.Lock lock= _locker.lock())
        {
            switch(_async)
            {
                case EXPIRING:
                    if (event.getThrowable()==null)
                    {
                        _async=Async.EXPIRED;
                        _event.addThrowable(new TimeoutException("Async API violation"));
                    }
                    else
                    {
                        _async=Async.ERRORING;
                    }
                    break;
                    
                case COMPLETE:
                case DISPATCH:
                    break;
                    
                default:
                    throw new IllegalStateException();
            }

            if (_state==State.ASYNC_WAIT)
            {
                _state=State.ASYNC_WOKEN;
                dispatch=true;
            }
        }

        if (dispatch)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Dispatch after async timeout {}",this);
            scheduleDispatch();
        }
    }

    public void complete()
    {
        // just like resume, except don't set _dispatched=true;
        boolean handle=false;
        AsyncContextEvent event=null;
        try(Locker.Lock lock= _locker.lock())
        {
            boolean started=false;
            event=_event;
            
            switch(_async)
            {
                case STARTED:
                    started=true;
                    break;
                case EXPIRING:
                case ERRORED:
                    break;
                default:
                    throw new IllegalStateException(this.getStatusStringLocked());
            }
            _async=Async.COMPLETE;
            
            if (started && _state==State.ASYNC_WAIT)
            {
                handle=true;
                _state=State.ASYNC_WOKEN;
            }
        }

        cancelTimeout(event);
        if (handle)
            runInContext(event,_channel);
    }

    public void errorComplete()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            _async=Async.COMPLETE;
            _event.setDispatchContext(null);
            _event.setDispatchPath(null);
        }

        cancelTimeout();
    }

    protected void onError()
    {
        final List<AsyncListener> aListeners;
        final AsyncContextEvent event;

        try(Locker.Lock lock= _locker.lock())
        {
            if (_state!=State.DISPATCHED/* || _async!=Async.ERRORING*/)
                throw new IllegalStateException(this.getStatusStringLocked());

            aListeners=_asyncListeners;
            event=_event;
            _async=Async.ERRORED;
        }

        if (event!=null && aListeners!=null)
        {
            event.getSuppliedRequest().setAttribute(RequestDispatcher.ERROR_EXCEPTION,event.getThrowable());
            event.getSuppliedRequest().setAttribute(RequestDispatcher.ERROR_MESSAGE,event.getThrowable().getMessage());
            for (AsyncListener listener : aListeners)
            {
                try
                {
                    listener.onError(event);
                }
                catch(Throwable x)
                {
                    LOG.info("Exception while invoking listener " + listener, x);
                }
            }
        }
    }


    protected void onComplete()
    {
        final List<AsyncListener> aListeners;
        final AsyncContextEvent event;

        try(Locker.Lock lock= _locker.lock())
        {
            switch(_state)
            {
                case COMPLETING:
                    aListeners=_asyncListeners;
                    event=_event;
                    _state=State.COMPLETED;
                    _async=Async.NOT_ASYNC;
                    break;

                default:
                    throw new IllegalStateException(this.getStatusStringLocked());
            }
        }

        if (event!=null)
        {
            if (aListeners!=null)
            {
                Runnable callback = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        for (AsyncListener listener : aListeners)
                        {
                            try
                            {
                                listener.onComplete(event);
                            }
                            catch(Throwable e)
                            {
                                LOG.warn(e);
                            }
                        }
                    }    
                    @Override
                    public String toString()
                    {
                        return "onComplete";
                    }
                };
                
                runInContext(event,callback);                
            }
            event.completed();
        }
    }

    protected void recycle()
    {
        cancelTimeout();
        try(Locker.Lock lock= _locker.lock())
        {
            switch(_state)
            {
                case DISPATCHED:
                case ASYNC_IO:
                    throw new IllegalStateException(getStatusStringLocked());
                case UPGRADED:
                    return;
                default:
                    break;
            }
            _asyncListeners=null;
            _state=State.IDLE;
            _async=Async.NOT_ASYNC;
            _initial=true;
            _asyncReadPossible=false;
            _asyncRead=Interest.NONE;
            _asyncWritePossible=false;
            _timeoutMs=DEFAULT_TIMEOUT;
            _event=null;
        }
    }

    public void upgrade()
    {
        cancelTimeout();
        try(Locker.Lock lock= _locker.lock())
        {
            switch(_state)
            {
                case IDLE:
                case COMPLETED:
                    break;
                default:
                    throw new IllegalStateException(getStatusStringLocked());
            }
            _asyncListeners=null;
            _state=State.UPGRADED;
            _async=Async.NOT_ASYNC;
            _initial=true;
            _asyncReadPossible=false;
            _asyncRead=Interest.NONE;
            _asyncWritePossible=false;
            _timeoutMs=DEFAULT_TIMEOUT;
            _event=null;
        }
    }

    protected void scheduleDispatch()
    {
        _channel.execute(_channel);
    }

    protected void cancelTimeout()
    {
        final AsyncContextEvent event;
        try(Locker.Lock lock= _locker.lock())
        {
            event=_event;
        }
        cancelTimeout(event);
    }

    protected void cancelTimeout(AsyncContextEvent event)
    {
        if (event!=null)
            event.cancelTimeoutTask();
    }
    
    public boolean isIdle()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return _state==State.IDLE;
        }
    }

    public boolean isExpired()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return _async==Async.EXPIRED;
        }
    }

    public boolean isInitial()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return _initial;
        }
    }

    public boolean isSuspended()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return _state==State.ASYNC_WAIT || _state==State.DISPATCHED && _async==Async.STARTED;
        }
    }

    boolean isCompleting()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return _state==State.COMPLETING;
        }
    }

    boolean isCompleted()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return _state == State.COMPLETED;
        }
    }

    public boolean isAsyncStarted()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            if (_state==State.DISPATCHED)
                return _async!=Async.NOT_ASYNC;
            return _async==Async.STARTED || _async==Async.EXPIRING;
        }
    }

    public boolean isAsync()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return !_initial || _async!=Async.NOT_ASYNC;
        }
    }

    public Request getBaseRequest()
    {
        return _channel.getRequest();
    }

    public HttpChannel getHttpChannel()
    {
        return _channel;
    }

    public ContextHandler getContextHandler()
    {
        final AsyncContextEvent event;
        try(Locker.Lock lock= _locker.lock())
        {
            event=_event;
        }
        return getContextHandler(event);
    }

    ContextHandler getContextHandler(AsyncContextEvent event)
    {
        if (event!=null)
        {
            Context context=((Context)event.getServletContext());
            if (context!=null)
                return context.getContextHandler();
        }
        return null;
    }

    public ServletResponse getServletResponse()
    {
        final AsyncContextEvent event;
        try(Locker.Lock lock= _locker.lock())
        {
            event=_event;
        }
        return getServletResponse(event);
    }
    
    public ServletResponse getServletResponse(AsyncContextEvent event)
    {
        if (event!=null && event.getSuppliedResponse()!=null)
            return event.getSuppliedResponse();
        return _channel.getResponse();
    }
    
    void runInContext(AsyncContextEvent event,Runnable runnable)
    {
        ContextHandler contextHandler = getContextHandler(event);
        if (contextHandler==null)
            runnable.run();
        else
            contextHandler.handle(_channel.getRequest(),runnable);
    }
    
    public Object getAttribute(String name)
    {
        return _channel.getRequest().getAttribute(name);
    }

    public void removeAttribute(String name)
    {
        _channel.getRequest().removeAttribute(name);
    }

    public void setAttribute(String name, Object attribute)
    {
        _channel.getRequest().setAttribute(name,attribute);
    }


    /* ------------------------------------------------------------ */
    /** Called to signal async read isReady() has returned false.
     * This indicates that there is no content available to be consumed
     * and that once the channel enters the ASYNC_WAIT state it will
     * register for read interest by calling {@link HttpChannel#onAsyncWaitForContent()}
     * either from this method or from a subsequent call to {@link #unhandle()}.
     */
    public void onReadUnready()
    {
        boolean interested=false;
        try(Locker.Lock lock= _locker.lock())
        {
            // We were already unready, this is not a state change, so do nothing
            if (_asyncRead!=Interest.REGISTERED)
            {
                _asyncReadPossible=false; // Assumes this has been checked in isReady() with lock held
                if (_state==State.ASYNC_WAIT)
                {
                    interested=true;
                    _asyncRead=Interest.REGISTERED;
                }
                else
                    _asyncRead=Interest.NEEDED;
            }
        }

        if (interested)
            _channel.onAsyncWaitForContent();
    }

    /* ------------------------------------------------------------ */
    /** Called to signal that content is now available to read.
     * If the channel is in ASYNC_WAIT state and unready (ie isReady() has
     * returned false), then the state is changed to ASYNC_WOKEN and true
     * is returned.
     * @return True IFF the channel was unready and in ASYNC_WAIT state
     */
    public boolean onReadPossible()
    {
        boolean woken=false;
        try(Locker.Lock lock= _locker.lock())
        {
            _asyncReadPossible=true;
            if (_state==State.ASYNC_WAIT && _asyncRead.isInterested())
            {
                woken=true;
                _state=State.ASYNC_WOKEN;
            }
        }
        return woken;
    }

    /* ------------------------------------------------------------ */
    /** Called to signal that the channel is ready for a callback.
     * This is similar to calling {@link #onReadUnready()} followed by
     * {@link #onReadPossible()}, except that as content is already
     * available, read interest is never set.
     * @return true if woken
     */
    public boolean onReadReady()
    {
        boolean woken=false;
        try(Locker.Lock lock= _locker.lock())
        {
            _asyncRead=Interest.REGISTERED;
            _asyncReadPossible=true;
            if (_state==State.ASYNC_WAIT)
            {
                woken=true;
                _state=State.ASYNC_WOKEN;
            }
        }
        return woken;
    }

    public boolean isReadPossible()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return _asyncReadPossible;
        }
    }

    public boolean onWritePossible()
    {
        boolean handle=false;

        try(Locker.Lock lock= _locker.lock())
        {
            _asyncWritePossible=true;
            if (_state==State.ASYNC_WAIT)
            {
                _state=State.ASYNC_WOKEN;
                handle=true;
            }
        }

        return handle;
    }

}
