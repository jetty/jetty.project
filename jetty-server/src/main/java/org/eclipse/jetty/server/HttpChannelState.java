//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import javax.servlet.AsyncListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * Implementation of AsyncContext interface that holds the state of request-response cycle.
 *
 * <table>
 * <tr><th>STATE</th><th colspan=6>ACTION</th></tr>
 * <tr><th></th>                           <th>handling()</th>  <th>startAsync()</th><th>unhandle()</th>  <th>dispatch()</th>   <th>complete()</th>      <th>completed()</th></tr>
 * <tr><th align=right>IDLE:</th>          <td>DISPATCHED</td>  <td></td>            <td></td>            <td></td>             <td>COMPLETECALLED??</td><td></td></tr>
 * <tr><th align=right>DISPATCHED:</th>    <td></td>            <td>ASYNCSTARTED</td><td>COMPLETING</td>  <td></td>             <td></td>                <td></td></tr>
 * <tr><th align=right>ASYNCSTARTED:</th>  <td></td>            <td></td>            <td>ASYNCWAIT</td>   <td>REDISPATCHING</td><td>COMPLETECALLED</td>  <td></td></tr>
 * <tr><th align=right>REDISPATCHING:</th> <td></td>            <td></td>            <td>REDISPATCHED</td><td></td>             <td></td>                <td></td></tr>
 * <tr><th align=right>ASYNCWAIT:</th>     <td></td>            <td></td>            <td></td>            <td>REDISPATCH</td>   <td>COMPLETECALLED</td>  <td></td></tr>
 * <tr><th align=right>REDISPATCH:</th>    <td>REDISPATCHED</td><td></td>            <td></td>            <td></td>             <td></td>                <td></td></tr>
 * <tr><th align=right>REDISPATCHED:</th>  <td></td>            <td>ASYNCSTARTED</td><td>COMPLETING</td>  <td></td>             <td></td>                <td></td></tr>
 * <tr><th align=right>COMPLETECALLED:</th><td>COMPLETING</td>  <td></td>            <td>COMPLETING</td>  <td></td>             <td></td>                <td></td></tr>
 * <tr><th align=right>COMPLETING:</th>    <td>COMPLETING</td>  <td></td>            <td></td>            <td></td>             <td></td>                <td>COMPLETED</td></tr>
 * <tr><th align=right>COMPLETED:</th>     <td></td>            <td></td>            <td></td>            <td></td>             <td></td>                <td></td></tr>
 * </table>
 */
public class HttpChannelState
{
    private static final Logger LOG = Log.getLogger(HttpChannelState.class);

    private final static long DEFAULT_TIMEOUT=30000L;

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

    private final HttpChannel<?> _channel;
    private List<AsyncListener> _lastAsyncListeners;
    private List<AsyncListener> _asyncListeners;

    private State _state;
    private boolean _initial;
    private boolean _dispatched;
    private boolean _expired;
    private volatile boolean _responseWrapped;
    private long _timeoutMs=DEFAULT_TIMEOUT;
    private AsyncContextEvent _event;

    protected HttpChannelState(HttpChannel<?> channel)
    {
        _channel=channel;
        _state=State.IDLE;
        _initial=true;
    }

    public State getState()
    {
        synchronized(this)
        {
            return _state;
        }
    }

    public void addListener(AsyncListener listener)
    {
        synchronized(this)
        {
            if (_asyncListeners==null)
                _asyncListeners=new ArrayList<>();
            _asyncListeners.add(listener);
        }
    }

    public void setTimeout(long ms)
    {
        synchronized(this)
        {
            _timeoutMs=ms;
        }
    }

    public long getTimeout()
    {
        synchronized(this)
        {
            return _timeoutMs;
        }
    }

    public AsyncContextEvent getAsyncContextEvent()
    {
        synchronized(this)
        {
            return _event;
        }
    }

    @Override
    public String toString()
    {
        synchronized (this)
        {
            return super.toString()+"@"+getStatusString();
        }
    }

    public String getStatusString()
    {
        synchronized (this)
        {
            return _state+
            (_initial?",initial":"")+
            (_dispatched?",resumed":"")+
            (_expired?",expired":"");
        }
    }

    /**
     * @return true if the handling of the request should proceed
     */
    protected boolean handling()
    {
        synchronized (this)
        {
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
                    break;

                case COMPLETECALLED:
                    _state=State.COMPLETING;
                    return false;

                case ASYNCWAIT:
                case COMPLETING:
                case COMPLETED:
                    return false;

                case REDISPATCH:
                    _state=State.REDISPATCHED;
                    break;

                default:
                    throw new IllegalStateException(this.getStatusString());
            }

            _responseWrapped=false;
            return true;

        }
    }


    public void startAsync(AsyncContextEvent event)
    {
        synchronized (this)
        {
            switch(_state)
            {
                case DISPATCHED:
                case REDISPATCHED:
                    _dispatched=false;
                    _expired=false;
                    _responseWrapped=event.getSuppliedResponse()!=_channel.getResponse();
                    _responseWrapped=false;
                    _event=event;
                    _state=State.ASYNCSTARTED;
                    List<AsyncListener> listeners=_lastAsyncListeners;
                    _lastAsyncListeners=_asyncListeners;
                    if (listeners!=null)
                        listeners.clear();
                    _asyncListeners=listeners;
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

    protected void error(Throwable th)
    {
        synchronized (this)
        {
            if (_event!=null)
                _event.setThrowable(th);
        }
    }

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

    public void dispatch(ServletContext context, String path)
    {
        boolean dispatch;
        synchronized (this)
        {
            
            switch(_state)
            {
                case ASYNCSTARTED:
                    _state=State.REDISPATCHING;
                    _event.setDispatchTarget(context,path);
                    _dispatched=true;
                    return;

                case ASYNCWAIT:
                    dispatch=!_expired;
                    _state=State.REDISPATCH;
                    _event.setDispatchTarget(context,path);
                    _dispatched=true;
                    break;

                case REDISPATCH:
                    _event.setDispatchTarget(context,path);
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

    public boolean isDispatched()
    {
        synchronized (this)
        {
            return _dispatched;
        }
    }

    protected void expired()
    {
        final List<AsyncListener> aListeners;
        synchronized (this)
        {
            switch(_state)
            {
                case ASYNCSTARTED:
                case ASYNCWAIT:
                    _expired=true;
                    aListeners=_asyncListeners;
                    break;
                default:
                    return;
            }
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

        synchronized (this)
        {
            switch(_state)
            {
                case ASYNCSTARTED:
                case ASYNCWAIT:
                    _state=State.REDISPATCH;
                    break;
                default:
                    _expired=false;
                    break;
            }
        }
        
        scheduleDispatch();
    }

    public void complete()
    {
        // just like resume, except don't set _dispatched=true;
        boolean dispatch;
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

    protected void completed()
    {
        final List<AsyncListener> aListeners;
        synchronized (this)
        {
            switch(_state)
            {
                case COMPLETING:
                    _state=State.COMPLETED;
                    aListeners=_asyncListeners;
                    break;

                default:
                    throw new IllegalStateException(this.getStatusString());
            }
        }

        if (aListeners!=null)
        {
            for (AsyncListener listener : aListeners)
            {
                try
                {
                    if (_event!=null && _event.getThrowable()!=null)
                    {
                        _event.getSuppliedRequest().setAttribute(RequestDispatcher.ERROR_EXCEPTION,_event.getThrowable());
                        _event.getSuppliedRequest().setAttribute(RequestDispatcher.ERROR_MESSAGE,_event.getThrowable().getMessage());
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
        _event.completed();
    }

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
            _dispatched=false;
            _expired=false;
            _responseWrapped=false;
            cancelTimeout();
            _timeoutMs=DEFAULT_TIMEOUT;
            _event=null;
        }
    }

    protected void scheduleDispatch()
    {
        _channel.execute(_channel);
    }

    protected void scheduleTimeout()
    {
        Scheduler scheduler = _channel.getScheduler();
        if (scheduler!=null && _timeoutMs>0)
            _event.setTimeoutTask(scheduler.schedule(new AsyncTimeout(),_timeoutMs,TimeUnit.MILLISECONDS));
    }

    protected void cancelTimeout()
    {
        AsyncContextEvent event=_event;
        if (event!=null)
            event.cancelTimeoutTask();
    }

    public boolean isExpired()
    {
        synchronized (this)
        {
            return _expired;
        }
    }
    
    public boolean isInitial()
    {
        synchronized(this)
        {
            return _initial;
        }
    }

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

    boolean isCompleting()
    {
        synchronized (this)
        {
            return _state==State.COMPLETING;
        }
    }

    public boolean isAsync()
    {
        synchronized (this)
        {
            switch(_state)
            {
                case ASYNCSTARTED:
                case REDISPATCHING:
                case ASYNCWAIT:
                case REDISPATCHED:
                case REDISPATCH:
                case COMPLETECALLED:
                    return true;

                default:
                    return false;
            }
        }
    }

    public Request getBaseRequest()
    {
        return _channel.getRequest();
    }

    public HttpChannel<?> getHttpChannel()
    {
        return _channel;
    }

    public ContextHandler getContextHandler()
    {
        final AsyncContextEvent event=_event;
        if (event!=null)
            return ((Context)event.getServletContext()).getContextHandler();
        return null;
    }

    public ServletResponse getServletResponse()
    {
        if (_responseWrapped && _event!=null && _event.getSuppliedResponse()!=null)
            return _event.getSuppliedResponse();
        return _channel.getResponse();
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

    public class AsyncTimeout implements Runnable
    {
        @Override
        public void run()
        {
            HttpChannelState.this.expired();
        }
    }

}
