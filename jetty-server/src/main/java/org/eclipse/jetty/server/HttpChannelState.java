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

import static javax.servlet.RequestDispatcher.ERROR_EXCEPTION;
import static javax.servlet.RequestDispatcher.ERROR_MESSAGE;
import static javax.servlet.RequestDispatcher.ERROR_STATUS_CODE;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.AsyncListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
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
     * The state of the HttpChannel,used to control the overall lifecycle.
     */
    public enum State
    {
        IDLE,             // Idle request
        DISPATCHED,       // Request dispatched to filter/servlet
        THROWN,           // Exception thrown while DISPATCHED
        ASYNC_WAIT,       // Suspended and waiting
        ASYNC_WOKEN,      // Dispatch to handle from ASYNC_WAIT
        ASYNC_IO,         // Dispatched for async IO
        ASYNC_ERROR,      // Async error from ASYNC_WAIT
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
     * The state of the servlet async API.
     */
    public enum Async
    {
        NOT_ASYNC,
        STARTED,          // AsyncContext.startAsync() has been called
        DISPATCH,         // AsyncContext.dispatch() has been called
        COMPLETE,         // AsyncContext.complete() has been called
        EXPIRING,         // AsyncContext timeout just happened
        EXPIRED,          // AsyncContext timeout has been processed
        ERRORING,         // An error just happened
        ERRORED           // The error has been processed
    }

    private final boolean DEBUG=LOG.isDebugEnabled();
    private final Locker _locker=new Locker();
    private final HttpChannel _channel;

    private List<AsyncListener> _asyncListeners;
    private State _state;
    private Async _async;
    private boolean _initial;
    private boolean _asyncReadPossible;
    private boolean _asyncReadUnready;
    private boolean _asyncWrite; // TODO refactor same as read
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
            return toStringLocked();
        }
    }

    public String toStringLocked()
    {
        return String.format("%s@%x{s=%s a=%s i=%b r=%s w=%b}",getClass().getSimpleName(),hashCode(),_state,_async,_initial,
                _asyncReadPossible?(_asyncReadUnready?"PU":"P!U"):(_asyncReadUnready?"!PU":"!P!U"),
                _asyncWrite);
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
        try(Locker.Lock lock= _locker.lock())
        {
            if(DEBUG)
                LOG.debug("handling {}",toStringLocked());
            
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
                    if (_asyncReadPossible)
                    {
                        _state=State.ASYNC_IO;
                        _asyncReadUnready=false;
                        return Action.READ_CALLBACK;
                    }

                    if (_asyncWrite)
                    {
                        _state=State.ASYNC_IO;
                        _asyncWrite=false;
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
                        case EXPIRED:
                        case ERRORED:
                            _state=State.DISPATCHED;
                            _async=Async.NOT_ASYNC;
                            return Action.ERROR_DISPATCH;
                        case STARTED:
                            case EXPIRING:
                        case ERRORING:
                            return Action.WAIT;
                        case NOT_ASYNC:
                            break;
                        default:
                            throw new IllegalStateException(getStatusStringLocked());
                    }

                    return Action.WAIT;

                case ASYNC_ERROR:
                    return Action.ASYNC_ERROR;

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
            if(DEBUG)
                LOG.debug("startAsync {}",toStringLocked());
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
                case ASYNC_ERROR:
                {
                    break;
                }
                case ASYNC_WAIT:
                {
                    _event.addThrowable(failure);
                    _state=State.ASYNC_ERROR;
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
     * For blocking connectors,this call may block if the request has
     * been suspended (startAsync called).
     * @return next actions
     * be handled again (eg because of a resume that happened before unhandle was called)
     */
    protected Action unhandle()
    {
        Action action;
        boolean read_interested=false;

        try(Locker.Lock lock= _locker.lock())
        {
            if(DEBUG)
                LOG.debug("unhandle {}",toStringLocked());
            
            switch(_state)
            {
                case COMPLETING:
                case COMPLETED:
                    return Action.TERMINATED;

                case THROWN:
                    _state=State.DISPATCHED;
                    return Action.ERROR_DISPATCH;
                    
                case DISPATCHED:
                case ASYNC_IO:
                case ASYNC_ERROR:
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

                case STARTED:
                    if (_asyncReadUnready && _asyncReadPossible)
                    {
                        _state=State.ASYNC_IO;
                        _asyncReadUnready=false;
                        action = Action.READ_CALLBACK;
                    }
                    else if (_asyncWrite) // TODO refactor same as read
                    {
                        _asyncWrite=false;
                        _state=State.ASYNC_IO;
                        action=Action.WRITE_CALLBACK;
                    }
                    else
                    {
                        _state=State.ASYNC_WAIT;
                        action=Action.WAIT; 
                        if (_asyncReadUnready)
                            read_interested=true;
                        Scheduler scheduler=_channel.getScheduler();
                        if (scheduler!=null && _timeoutMs>0)
                            _event.setTimeoutTask(scheduler.schedule(_event,_timeoutMs,TimeUnit.MILLISECONDS));
                    }
                    break;

                case EXPIRING:
                    // onTimeout callbacks still being called, so just WAIT
                    _state=State.ASYNC_WAIT;
                    action=Action.WAIT;
                    break;

                case EXPIRED:
                    // onTimeout handling is complete, but did not dispatch as
                    // we were handling.  So do the error dispatch here
                    _state=State.DISPATCHED;
                    _async=Async.NOT_ASYNC;
                    action=Action.ERROR_DISPATCH;
                    break;

                case ERRORED:
                    _state=State.DISPATCHED;
                    _async=Async.NOT_ASYNC;
                    action=Action.ERROR_DISPATCH;
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
            _channel.asyncReadFillInterested();

        return action;
    }

    public void dispatch(ServletContext context, String path)
    {
        boolean dispatch=false;
        AsyncContextEvent event;
        try(Locker.Lock lock= _locker.lock())
        {
            if(DEBUG)
                LOG.debug("dispatch {} -> {}",toStringLocked(),path);
            
            boolean started=false;
            event=_event;
            switch(_async)
            {
                case STARTED:
                    started=true;
                    break;
                case EXPIRING:
                case ERRORING:
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
            if(DEBUG)
                LOG.debug("onTimeout {}",toStringLocked());
            
            if (_async!=Async.STARTED)
                return;
            _async=Async.EXPIRING;
            event=_event;
            listeners=_asyncListeners;

        }

        final AtomicReference<Throwable> error=new AtomicReference<>();
        if (listeners!=null)
        {
            Runnable task=new Runnable()
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
                        catch(Throwable x)
                        {
                            LOG.warn(x+" while invoking onTimeout listener " + listener);
                            LOG.debug(x);
                            if (error.get()==null)
                                error.set(x);
                            else
                                error.get().addSuppressed(x);
                        }
                    }
                }
                @Override
                public String toString()
                {
                    return "onTimeout";
                }
            };
            
            runInContext(event,task);
        }

        Throwable th=error.get();
        boolean dispatch=false;
        try(Locker.Lock lock= _locker.lock())
        {
            switch(_async)
            {
                case EXPIRING:
                    _async=th==null ? Async.EXPIRED : Async.ERRORING;
                    break;

                case COMPLETE:
                case DISPATCH:
                    if (th!=null)
                    {
                        LOG.ignore(th);
                        th=null;
                    }
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

        if (th!=null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Error after async timeout {}",this,th);
            onError(th);
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
        AsyncContextEvent event;
        try(Locker.Lock lock= _locker.lock())
        {
            if(DEBUG)
                LOG.debug("complete {}",toStringLocked());
            
            boolean started=false;
            event=_event;
            
            switch(_async)
            {
                case STARTED:
                    started=true;
                    break;
                case EXPIRING:
                case ERRORING:
                case ERRORED:
                    break;
                case COMPLETE:
                    return;
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
            if(DEBUG)
                LOG.debug("error complete {}",toStringLocked());
            
            _async=Async.COMPLETE;
            _event.setDispatchContext(null);
            _event.setDispatchPath(null);
        }

        cancelTimeout();
    }
    
    protected void onError(Throwable failure)
    {
        final List<AsyncListener> listeners;
        final AsyncContextEvent event;
        final Request baseRequest = _channel.getRequest();
        
        int code=HttpStatus.INTERNAL_SERVER_ERROR_500;
        String reason=null;
        if (failure instanceof BadMessageException)
        {
            BadMessageException bme = (BadMessageException)failure;
            code = bme.getCode();
            reason = bme.getReason();
        }
        else if (failure instanceof UnavailableException)
        {
            if (((UnavailableException)failure).isPermanent())
                code = HttpStatus.NOT_FOUND_404;
            else
                code = HttpStatus.SERVICE_UNAVAILABLE_503;
        }
        
        try(Locker.Lock lock= _locker.lock())
        {
            if(DEBUG)
                LOG.debug("onError {} {}",toStringLocked(),failure);
            
            // Set error on request.
            if(_event!=null)
            {
                _event.addThrowable(failure);
                _event.getSuppliedRequest().setAttribute(ERROR_STATUS_CODE,code);
                _event.getSuppliedRequest().setAttribute(ERROR_EXCEPTION,failure);
                _event.getSuppliedRequest().setAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE,failure==null?null:failure.getClass());
                    
                _event.getSuppliedRequest().setAttribute(ERROR_MESSAGE,reason!=null?reason:null);
            }
            else
            {
                Throwable error = (Throwable)baseRequest.getAttribute(ERROR_EXCEPTION);
                if (error!=null)
                    throw new IllegalStateException("Error already set",error);
                baseRequest.setAttribute(ERROR_STATUS_CODE,code);
                baseRequest.setAttribute(ERROR_EXCEPTION,failure);
                baseRequest.setAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE,failure==null?null:failure.getClass());
                baseRequest.setAttribute(ERROR_MESSAGE,reason!=null?reason:null);
            }
            
            // Are we blocking?
            if (_async==Async.NOT_ASYNC)
            {
                // Only called from within HttpChannel Handling, so much be dispatched, let's stay dispatched!
                if (_state==State.DISPATCHED)
                {
                    _state=State.THROWN;
                    return;
                }
                throw new IllegalStateException(this.getStatusStringLocked());
            }
            
            // We are Async
            _async=Async.ERRORING;
            listeners=_asyncListeners;
            event=_event;
        }

        if(listeners!=null)
        {
            Runnable task=new Runnable()
            {
                @Override
                public void run()
                {
                    for (AsyncListener listener : listeners)
                    {
                        try
                        {
                            listener.onError(event);
                        }
                        catch (Throwable x)
                        {
                            LOG.warn(x+" while invoking onError listener " + listener);
                            LOG.debug(x);
                        }
                    }
                }

                @Override
                public String toString()
                {
                    return "onError";
                }
            };
            runInContext(event,task);
        }

        boolean dispatch=false;
        try(Locker.Lock lock= _locker.lock())
        {
            switch(_async)
            {
                case ERRORING:
                {
                    // Still in this state ? The listeners did not invoke API methods
                    // and the container must provide a default error dispatch.
                    _async=Async.ERRORED;
                    break;
                }
                case DISPATCH:
                case COMPLETE:
                {
                    // The listeners called dispatch() or complete().
                    break;
                }
                default:
                {
                    throw new IllegalStateException(toString());
                }
            }

            if(_state==State.ASYNC_WAIT)
            {
                _state=State.ASYNC_WOKEN;
                dispatch=true;
            }
        }

        if(dispatch)
        {
            if(LOG.isDebugEnabled())
                LOG.debug("Dispatch after error {}",this);
            scheduleDispatch();
        }
    }

    protected void onComplete()
    {
        final List<AsyncListener> aListeners;
        final AsyncContextEvent event;

        try(Locker.Lock lock= _locker.lock())
        {
            if(DEBUG)
                LOG.debug("onComplete {}",toStringLocked());
            
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
                                LOG.warn(e+" while invoking onComplete listener " + listener);
                                LOG.debug(e);
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
            if(DEBUG)
                LOG.debug("recycle {}",toStringLocked());
            
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
            _asyncReadPossible=_asyncReadUnready=false;
            _asyncWrite=false;
            _timeoutMs=DEFAULT_TIMEOUT;
            _event=null;
        }
    }

    public void upgrade()
    {
        cancelTimeout();
        try(Locker.Lock lock= _locker.lock())
        {
            if(DEBUG)
                LOG.debug("upgrade {}",toStringLocked());
            
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
            _asyncReadPossible=_asyncReadUnready=false;
            _asyncWrite=false;
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

    public boolean isAsyncComplete()
    {
        try(Locker.Lock lock= _locker.lock())
        {
            return _async==Async.COMPLETE;
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
     * and that once the channel enteres the ASYNC_WAIT state it will
     * register for read interest by calling {@link HttpChannel#asyncReadFillInterested()}
     * either from this method or from a subsequent call to {@link #unhandle()}.
     */
    public void onReadUnready()
    {
        boolean interested=false;
        try(Locker.Lock lock= _locker.lock())
        {
            if(DEBUG)
                LOG.debug("onReadUnready {}",toStringLocked());
            
            // We were already unready, this is not a state change, so do nothing
            if (!_asyncReadUnready)
            {
                _asyncReadUnready=true;
                _asyncReadPossible=false; // Assumes this has been checked in isReady() with lock held
                if (_state==State.ASYNC_WAIT)
                    interested=true;
            }
        }

        if (interested)
            _channel.asyncReadFillInterested();
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
            if(DEBUG)
                LOG.debug("onReadPossible {}",toStringLocked());
            
            _asyncReadPossible=true;
            if (_state==State.ASYNC_WAIT && _asyncReadUnready)
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
            if(DEBUG)
                LOG.debug("onReadReady {}",toStringLocked());
            
            _asyncReadUnready=true;
            _asyncReadPossible=true;
            if (_state==State.ASYNC_WAIT)
            {
                woken=true;
                _state=State.ASYNC_WOKEN;
            }
        }
        return woken;
    }
    
    /* ------------------------------------------------------------ */
    /** Called to signal that a read has read -1.
     * Will wake if the read was called while in ASYNC_WAIT state
     * @return true if woken
     */
    public boolean onReadEof()
    {
        boolean woken=false;
        try(Locker.Lock lock= _locker.lock())
        {
            if(DEBUG)
                LOG.debug("onReadEof {}",toStringLocked());
            
            if (_state==State.ASYNC_WAIT)
            {
                _state=State.ASYNC_WOKEN;
                _asyncReadUnready=true;
                _asyncReadPossible=true;
                woken=true;
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
            if(DEBUG)
                LOG.debug("onWritePossible {}",toStringLocked());
            
            _asyncWrite=true;
            if (_state==State.ASYNC_WAIT)
            {
                _state=State.ASYNC_WOKEN;
                handle=true;
            }
        }

        return handle;
    }

}
