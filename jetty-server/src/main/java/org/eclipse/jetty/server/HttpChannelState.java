//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import javax.servlet.ServletContext;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.Scheduler;

import static javax.servlet.RequestDispatcher.ERROR_EXCEPTION;
import static javax.servlet.RequestDispatcher.ERROR_EXCEPTION_TYPE;
import static javax.servlet.RequestDispatcher.ERROR_MESSAGE;
import static javax.servlet.RequestDispatcher.ERROR_REQUEST_URI;
import static javax.servlet.RequestDispatcher.ERROR_SERVLET_NAME;
import static javax.servlet.RequestDispatcher.ERROR_STATUS_CODE;

/**
 * Implementation of AsyncContext interface that holds the state of request-response cycle.
 */
public class HttpChannelState
{
    private static final Logger LOG = Log.getLogger(HttpChannelState.class);

    private static final long DEFAULT_TIMEOUT = Long.getLong("org.eclipse.jetty.server.HttpChannelState.DEFAULT_TIMEOUT", 30000L);

    /**
     * The state of the HttpChannel,used to control the overall lifecycle.
     */
    public enum State
    {
        IDLE,         // Idle request
        HANDLING,     // Request dispatched to filter/servlet or Async IO callback
        WAITING,      // Suspended and waiting
        WAKING,       // Dispatch to handle from ASYNC_WAIT
        UPGRADED      // Request upgraded the connection
    }

    /**
     * The state of the servlet async API.
     */
    private enum LifeCycleState
    {
        BLOCKING,
        ASYNC,            // AsyncContext.startAsync() has been called
        DISPATCH,         // AsyncContext.dispatch() has been called
        EXPIRE,           // AsyncContext timeout has happened
        EXPIRING,         // AsyncListeners are being called
        COMPLETE,         // AsyncContext.complete() has been called
        COMPLETING,       // Response is completable
        COMPLETED         // Response is completed
    }

    private enum AsyncReadState
    {
        IDLE,           // No isReady; No data
        REGISTER,       // isReady()==false handling; No data
        REGISTERED,     // isReady()==false !handling; No data
        POSSIBLE,       // isReady()==false async read callback called (http/1 only)
        PRODUCING,      // isReady()==false READ_PRODUCE action is being handled (http/1 only)
        READY           // isReady() was false, onContentAdded has been called
    }

    private enum ResponseState
    {
        OPEN,
        COMMITTED,
        COMPLETED,
        ABORTED,
    }

    /**
     * The actions to take as the channel moves from state to state.
     */
    public enum Action
    {
        NOOP,             // No action 
        DISPATCH,         // handle a normal request dispatch
        ASYNC_DISPATCH,   // handle an async request dispatch
        ERROR_DISPATCH,   // handle a normal error
        ASYNC_ERROR,      // handle an async error
        ASYNC_TIMEOUT,    // call asyncContext onTimeout
        WRITE_CALLBACK,   // handle an IO write callback
        READ_PRODUCE,     // Check is a read is possible by parsing/filling
        READ_CALLBACK,    // handle an IO read callback
        COMPLETE,         // Complete the response
        TERMINATED,       // No further actions
        WAIT,             // Wait for further events
    }

    private final Locker _locker = new Locker();
    private final HttpChannel _channel;
    private List<AsyncListener> _asyncListeners;
    private State _state;
    private LifeCycleState _lifeCycleState;
    private ResponseState _responseState;
    private AsyncReadState _asyncReadState = AsyncReadState.IDLE;
    private boolean _initial;
    private boolean _asyncWritePossible;
    private long _timeoutMs = DEFAULT_TIMEOUT;
    private AsyncContextEvent _event;
    private boolean _sendError;

    protected HttpChannelState(HttpChannel channel)
    {
        _channel = channel;
        _state = State.IDLE;
        _lifeCycleState = LifeCycleState.BLOCKING;
        _responseState = ResponseState.OPEN;
        _initial = true;
    }

    public State getState()
    {
        synchronized (this)
        {
            return _state;
        }
    }

    public void addListener(AsyncListener listener)
    {
        synchronized (this)
        {
            if (_asyncListeners == null)
                _asyncListeners = new ArrayList<>();
            _asyncListeners.add(listener);
        }
    }

    public boolean hasListener(AsyncListener listener)
    {
        synchronized (this)
        {
            if (_asyncListeners == null)
                return false;
            for (AsyncListener l : _asyncListeners)
            {
                if (l == listener)
                    return true;

                if (l instanceof AsyncContextState.WrappedAsyncListener && ((AsyncContextState.WrappedAsyncListener)l).getListener() == listener)
                    return true;
            }

            return false;
        }
    }

    public boolean isSendError()
    {
        synchronized (this)
        {
            return _sendError;
        }
    }

    public void setTimeout(long ms)
    {
        synchronized (this)
        {
            _timeoutMs = ms;
        }
    }

    public long getTimeout()
    {
        synchronized (this)
        {
            return _timeoutMs;
        }
    }

    public AsyncContextEvent getAsyncContextEvent()
    {
        synchronized (this)
        {
            return _event;
        }
    }

    @Override
    public String toString()
    {
        synchronized (this)
        {
            return toStringLocked();
        }
    }

    public String toStringLocked()
    {
        return String.format("%s@%x{%s}",
            getClass().getSimpleName(),
            hashCode(),
            getStatusStringLocked());
    }

    private String getStatusStringLocked()
    {
        return String.format("s=%s lc=%s rs=%s ars=%s awp=%b se=%b i=%b",
            _state,
            _lifeCycleState,
            _responseState,
            _asyncReadState,
            _asyncWritePossible,
            _sendError,
            _initial);
    }

    public String getStatusString()
    {
        synchronized (this)
        {
            return getStatusStringLocked();
        }
    }

    public boolean commitResponse()
    {
        synchronized (this)
        {
            switch(_responseState)
            {
                case OPEN:
                    _responseState = ResponseState.COMMITTED;
                    return true;

                default:
                    return false;
            }
        }
    }

    public boolean partialResponse()
    {
        synchronized (this)
        {
            switch(_responseState)
            {
                case COMMITTED:
                    _responseState = ResponseState.OPEN;
                    return true;

                default:
                    return false;
            }
        }
    }

    public boolean completeResponse()
    {
        synchronized (this)
        {
            switch(_responseState)
            {
                case OPEN:
                case COMMITTED:
                    _responseState = ResponseState.COMPLETED;
                    return true;

                default:
                    return false;
            }
        }
    }

    public boolean isResponseCommitted()
    {
        synchronized (this)
        {
            switch (_responseState)
            {
                case OPEN:
                    return false;
                default:
                    return true;
            }
        }
    }

    public boolean isResponseCompleted()
    {
        synchronized (this)
        {
            return _responseState == ResponseState.COMPLETED;
        }
    }

    public boolean abortResponse()
    {
        synchronized (this)
        {
            switch(_responseState)
            {
                case ABORTED:
                    return false;
                case OPEN:
                    // No response has been committed
                    // TODO we need a better way to signal to the request log that an abort was done
                    _channel.getResponse().setStatus(500);
                    _responseState = ResponseState.ABORTED;
                    return true;

                default:
                    _responseState = ResponseState.ABORTED;
                    return true;
            }
        }
    }

    /**
     * @return Next handling of the request should proceed
     */
    public Action handling()
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("handling {}", toStringLocked());

            switch (_state)
            {
                case IDLE:
                    if (_lifeCycleState != LifeCycleState.BLOCKING)
                        throw new IllegalStateException(getStatusStringLocked());
                    _initial = true;
                    _state = State.HANDLING;
                    return Action.DISPATCH;

                case WAKING:
                    if (_event != null && _event.getThrowable() != null && !_sendError)
                        return Action.ASYNC_ERROR;

                    Action action = nextAction(true);
                    if (LOG.isDebugEnabled())
                        LOG.debug("nextAction(true) {} {}", action, toStringLocked());
                    return action;

                case WAITING:
                case HANDLING:
                case UPGRADED:
                default:
                    throw new IllegalStateException(getStatusStringLocked());
            }
        }
    }

    /**
     * Signal that the HttpConnection has finished handling the request.
     * For blocking connectors, this call may block if the request has
     * been suspended (startAsync called).
     *
     * @return next actions
     * be handled again (eg because of a resume that happened before unhandle was called)
     */
    protected Action unhandle()
    {
        boolean readInterested = false;

        synchronized (this)
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("unhandle {}", toStringLocked());

                switch (_state)
                {
                    case HANDLING:
                        break;

                    default:
                        throw new IllegalStateException(this.getStatusStringLocked());
                }

                _initial = false;

                Action action = nextAction(false);
                if (LOG.isDebugEnabled())
                    LOG.debug("nextAction(false) {} {}", action, toStringLocked());
                return action;
            }
            finally
            {
                if (_state == State.WAITING)
                {
                    switch(_asyncReadState)
                    {
                        case REGISTER:
                        case PRODUCING:
                            _channel.onAsyncWaitForContent();
                        default:
                            break;
                    }
                }
            }
        }
    }

    private Action nextAction(boolean handling)
    {
        if (_sendError)
        {
            _state = State.HANDLING;
            _lifeCycleState = LifeCycleState.BLOCKING;
            _sendError = false;
            return Action.ERROR_DISPATCH;
        }

        switch (_lifeCycleState)
        {
            case BLOCKING:
                if (handling)
                    throw new IllegalStateException(getStatusStringLocked());
                _state = State.HANDLING;
                _lifeCycleState = LifeCycleState.COMPLETING;
                return Action.COMPLETE;

            case ASYNC:
                switch (_asyncReadState)
                {
                    case POSSIBLE:
                        _state = State.HANDLING;
                        _asyncReadState = AsyncReadState.PRODUCING;
                        return Action.READ_PRODUCE;
                    case READY:
                        _state = State.HANDLING;
                        _asyncReadState = AsyncReadState.IDLE;
                        return Action.READ_CALLBACK;
                    case REGISTER:
                    case PRODUCING:
                    case IDLE:
                    case REGISTERED:
                        break;
                    default:
                        throw new IllegalStateException(getStatusStringLocked());
                }

                if (_asyncWritePossible)
                {
                    _state = State.HANDLING;
                    _asyncWritePossible = false;
                    return Action.WRITE_CALLBACK;
                }

                if (handling)
                    throw new IllegalStateException(getStatusStringLocked());

                Scheduler scheduler = _channel.getScheduler();
                if (scheduler != null && _timeoutMs > 0 && !_event.hasTimeoutTask())
                    _event.setTimeoutTask(scheduler.schedule(_event, _timeoutMs, TimeUnit.MILLISECONDS));
                _state = State.WAITING;
                return Action.WAIT;

            case DISPATCH:
                _state = State.HANDLING;
                _lifeCycleState = LifeCycleState.BLOCKING;
                return Action.ASYNC_DISPATCH;

            case EXPIRE:
                _state = State.HANDLING;
                _lifeCycleState = LifeCycleState.EXPIRING;
                return Action.ASYNC_TIMEOUT;

            case EXPIRING:
                if (handling)
                    throw new IllegalStateException(getStatusStringLocked());

                // We must have already called onTimeout and nothing changed,
                // so we will do a normal error dispatch
                _state = State.HANDLING;
                _lifeCycleState = LifeCycleState.BLOCKING;

                final Request request = _channel.getRequest();
                ContextHandler.Context context = _event.getContext();
                if (context != null)
                    request.setAttribute(ErrorHandler.ERROR_CONTEXT, context);
                request.setAttribute(ERROR_REQUEST_URI, request.getRequestURI());
                request.setAttribute(ERROR_STATUS_CODE, 500);
                request.setAttribute(ERROR_MESSAGE, "AsyncContext timeout");
                return Action.ERROR_DISPATCH;

            case COMPLETE:
                _state = State.HANDLING;
                _lifeCycleState = LifeCycleState.COMPLETING;
                return Action.COMPLETE;

            case COMPLETING:
                if (handling)
                {
                    _state = State.HANDLING;
                    return Action.COMPLETE;
                }

                _state = State.WAITING;
                return Action.WAIT;

            case COMPLETED:
                _state = State.IDLE;
                _lifeCycleState = LifeCycleState.COMPLETED;
                return Action.TERMINATED;

            default:
                throw new IllegalStateException(getStatusStringLocked());
        }
    }


    public void startAsync(AsyncContextEvent event)
    {
        final List<AsyncListener> lastAsyncListeners;

        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("startAsync {}", toStringLocked());
            if (_state != State.HANDLING || _lifeCycleState != LifeCycleState.BLOCKING)
                throw new IllegalStateException(this.getStatusStringLocked());

            _lifeCycleState = LifeCycleState.ASYNC;
            _event = event;
            lastAsyncListeners = _asyncListeners;
            _asyncListeners = null;
        }

        if (lastAsyncListeners != null)
        {
            Runnable callback = new Runnable()
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
                        catch (Throwable e)
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

            runInContext(event, callback);
        }
    }

    public void dispatch(ServletContext context, String path)
    {
        boolean dispatch = false;
        AsyncContextEvent event;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("dispatch {} -> {}", toStringLocked(), path);

            // TODO this method can be simplified

            boolean started = false;
            event = _event;
            switch (_lifeCycleState)
            {
                case ASYNC:
                    started = true;
                    break;
                case EXPIRING:
                    break;
                default:
                    throw new IllegalStateException(this.getStatusStringLocked());
            }
            _lifeCycleState = LifeCycleState.DISPATCH;

            if (context != null)
                _event.setDispatchContext(context);
            if (path != null)
                _event.setDispatchPath(path);

            if (started)
            {
                switch (_state)
                {
                    case HANDLING:
                    case WAKING:
                        break;
                    case WAITING:
                        _state = State.WAKING;
                        dispatch = true;
                        break;
                    default:
                        LOG.warn("async dispatched when complete {}", this);
                        break;
                }
            }
        }

        cancelTimeout(event);
        if (dispatch)
            scheduleDispatch();
    }

    protected void timeout()
    {
        boolean dispatch = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Timeout {}", toStringLocked());

            if (_lifeCycleState != LifeCycleState.ASYNC)
                return;
            _lifeCycleState = LifeCycleState.EXPIRE;

            if (_state == State.WAITING)
            {
                _state = State.WAKING;
                dispatch = true;
            }
        }

        if (dispatch)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Dispatch after async timeout {}", this);
            scheduleDispatch();
        }
    }

    protected void onTimeout()
    {
        final List<AsyncListener> listeners;
        AsyncContextEvent event;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onTimeout {}", toStringLocked());
            if (_lifeCycleState != LifeCycleState.EXPIRING || _state != State.HANDLING)
                throw new IllegalStateException(toStringLocked());
            event = _event;
            listeners = _asyncListeners;
        }

        if (listeners != null)
        {
            Runnable task = new Runnable()
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
                        catch (Throwable x)
                        {
                            LOG.warn(x + " while invoking onTimeout listener " + listener);
                            LOG.debug(x);
                        }
                    }
                }

                @Override
                public String toString()
                {
                    return "onTimeout";
                }
            };

            runInContext(event, task);
        }
    }

    public void complete()
    {
        boolean handle = false;
        AsyncContextEvent event;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("complete {}", toStringLocked());

            event = _event;
            switch (_lifeCycleState)
            {
                case EXPIRING:
                case ASYNC:
                    _lifeCycleState = _sendError ? LifeCycleState.BLOCKING : LifeCycleState.COMPLETE;
                    break;

                case COMPLETE:
                    return;
                default:
                    throw new IllegalStateException(this.getStatusStringLocked());
            }
            if (_state == State.WAITING)
            {
                handle = true;
                _state = State.WAKING;
            }
        }

        cancelTimeout(event);
        if (handle)
            runInContext(event, _channel);
    }

    public void asyncError(Throwable failure)
    {
        // This method is called when an failure occurs asynchronously to
        // normal handling.  If the request is async, we arrange for the
        // exception to be thrown from the normal handling loop and then
        // actually handled by #thrownException

        AsyncContextEvent event = null;
        synchronized (this)
        {
            if (_state == State.WAITING && _lifeCycleState == LifeCycleState.ASYNC)
            {
                _event.addThrowable(failure);
                event = _event;
            }
            else
            {
                LOG.warn(failure.toString());
                LOG.debug(failure);
            }
        }

        if (event != null)
        {
            cancelTimeout(event);
            runInContext(event, _channel);
        }
    }

    protected void thrownException(Throwable th)
    {
        // This method is called by HttpChannel.handleException to handle an exception thrown from a dispatch:
        //  + If the request is async, then any async listeners are give a chance to handle the exception in their onError handler.
        //  + If the request is not async, or not handled by any async onError listener, then a normal sendError is done.

        Runnable sendError = () ->
        {
            final Request request = _channel.getRequest();

            // Determine the actual details of the exception
            final int code;
            final String message;
            Throwable cause = _channel.unwrap(th, BadMessageException.class, UnavailableException.class);
            if (cause == null)
            {
                code = HttpStatus.INTERNAL_SERVER_ERROR_500;
                message = th.toString();
            }
            else if (cause instanceof BadMessageException)
            {
                BadMessageException bme = (BadMessageException)cause;
                code = bme.getCode();
                message = bme.getReason();
            }
            else if (cause instanceof UnavailableException)
            {
                message = cause.toString();
                if (((UnavailableException)cause).isPermanent())
                    code = HttpStatus.NOT_FOUND_404;
                else
                    code = HttpStatus.SERVICE_UNAVAILABLE_503;
            }
            else
            {
                code = HttpStatus.INTERNAL_SERVER_ERROR_500;
                message = null;
            }

            request.setAttribute(ERROR_EXCEPTION, th);
            request.setAttribute(ERROR_EXCEPTION_TYPE, th.getClass());
            sendError(code, message);
        };

        final AsyncContextEvent asyncEvent;
        final List<AsyncListener> asyncListeners;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("thrownException " + getStatusStringLocked(), th);

            // This can only be called from within the handle loop
            if (_state != State.HANDLING)
                throw new IllegalStateException(getStatusStringLocked());

            if (_sendError)
            {
                LOG.warn("unhandled due to prior sendError", th);
                return;
            }

            // Check async state to determine type of handling
            switch (_lifeCycleState)
            {
                case BLOCKING:
                    // handle the exception with a sendError
                    sendError.run();
                    return;

                case DISPATCH:
                case COMPLETE:
                    // Complete or Dispatch have been called, but the original subsequently threw an exception.
                    // TODO // GW I think we really should ignore, but will fall through for now.
                    // TODO LOG.warn("unhandled due to prior dispatch/complete", th);
                    // TODO return;

                case ASYNC:
                    if (_asyncListeners == null || _asyncListeners.isEmpty())
                    {
                        sendError.run();
                        return;
                    }
                    asyncEvent = _event;
                    asyncEvent.addThrowable(th);
                    asyncListeners = _asyncListeners;
                    break;

                default:
                    LOG.warn("unhandled in state " + _lifeCycleState, new IllegalStateException(th));
                    return;
            }
        }

        // If we are async and have async listeners
        // call onError
        runInContext(asyncEvent, () ->
        {
            for (AsyncListener listener : asyncListeners)
            {
                try
                {
                    listener.onError(asyncEvent);
                }
                catch (Throwable x)
                {
                    LOG.warn(x + " while invoking onError listener " + listener);
                    LOG.debug(x);
                }
            }
        });

        // check the actions of the listeners
        synchronized (this)
        {
            // if anybody has called sendError then we've handled as much as we can by calling listeners
            if (_sendError)
                return;

            switch (_lifeCycleState)
            {
                case ASYNC:
                    // The listeners did not invoke API methods
                    // and the container must provide a default error dispatch.
                    sendError.run();
                    return;

                case DISPATCH:
                case COMPLETE:
                    // The listeners handled the exception by calling dispatch() or complete().
                    return;

                default:
                    LOG.warn("unhandled in state " + _lifeCycleState, new IllegalStateException(th));
                    return;
            }
        }
    }

    public void sendError(int code, String message)
    {
        // This method is called by Response.sendError to organise for an error page to be generated when it is possible:
        //  + The response is reset and temporarily closed.
        //  + The details of the error are saved as request attributes
        //  + The _sendError boolean is set to true so that an ERROR_DISPATCH action will be generated:
        //       - after unhandle for sync
        //       - after both unhandle and complete for async

        final Request request = _channel.getRequest();
        final Response response = _channel.getResponse();
        if (message == null)
            message = HttpStatus.getMessage(code);

        synchronized (this)
        {
            if (_responseState != ResponseState.OPEN)
                throw new IllegalStateException("Response is " + _responseState);
            response.resetContent(); // will throw ISE if committed
            response.getHttpOutput().sendErrorClose();

            request.getResponse().setStatus(code);
            // we are allowed to have a body, then produce the error page.
            ContextHandler.Context context = request.getErrorContext();
            if (context != null)
                request.setAttribute(ErrorHandler.ERROR_CONTEXT, context);
            request.setAttribute(ERROR_REQUEST_URI, request.getRequestURI());
            request.setAttribute(ERROR_SERVLET_NAME, request.getServletName());
            request.setAttribute(ERROR_STATUS_CODE, code);
            request.setAttribute(ERROR_MESSAGE, message);

            if (LOG.isDebugEnabled())
                LOG.debug("sendError {}", toStringLocked());

            switch (_state)
            {
                case HANDLING:
                case WAKING:
                case WAITING:
                    _sendError = true;
                    if (_event != null)
                    {
                        Throwable cause = (Throwable) request.getAttribute(ERROR_EXCEPTION);
                        if (cause != null)
                            _event.addThrowable(cause);
                    }
                    break;

                default:
                {
                    throw new IllegalStateException(getStatusStringLocked());
                }
            }
        }
    }

    protected void completed()
    {
        final List<AsyncListener> aListeners;
        final AsyncContextEvent event;

        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onComplete {}", toStringLocked());

            switch (_lifeCycleState)
            {
                case COMPLETING:
                    aListeners = _asyncListeners;
                    event = _event;
                    _lifeCycleState = LifeCycleState.COMPLETED;
                    break;

                default:
                    throw new IllegalStateException(this.getStatusStringLocked());
            }
        }

        if (event != null)
        {
            if (aListeners != null)
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
                            catch (Throwable e)
                            {
                                LOG.warn(e + " while invoking onComplete listener " + listener);
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

                runInContext(event, callback);
            }
            event.completed();
        }
    }

    protected void recycle()
    {
        cancelTimeout();
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("recycle {}", toStringLocked());

            switch (_state)
            {
                case HANDLING:
                    throw new IllegalStateException(getStatusStringLocked());
                case UPGRADED:
                    return;
                default:
                    break;
            }
            _asyncListeners = null;
            _state = State.IDLE;
            _lifeCycleState = LifeCycleState.BLOCKING;
            _responseState = ResponseState.OPEN;
            _initial = true;
            _asyncReadState = AsyncReadState.IDLE;
            _asyncWritePossible = false;
            _timeoutMs = DEFAULT_TIMEOUT;
            _event = null;
        }
    }

    public void upgrade()
    {
        cancelTimeout();
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("upgrade {}", toStringLocked());

            switch (_state)
            {
                case IDLE:
                    break;
                default:
                    throw new IllegalStateException(getStatusStringLocked());
            }
            _asyncListeners = null;
            _state = State.UPGRADED;
            _lifeCycleState = LifeCycleState.BLOCKING;
            _initial = true;
            _asyncReadState = AsyncReadState.IDLE;
            _asyncWritePossible = false;
            _timeoutMs = DEFAULT_TIMEOUT;
            _event = null;
        }
    }

    protected void scheduleDispatch()
    {
        _channel.execute(_channel);
    }

    protected void cancelTimeout()
    {
        final AsyncContextEvent event;
        synchronized (this)
        {
            event = _event;
        }
        cancelTimeout(event);
    }

    protected void cancelTimeout(AsyncContextEvent event)
    {
        if (event != null)
            event.cancelTimeoutTask();
    }

    public boolean isIdle()
    {
        synchronized (this)
        {
            return _state == State.IDLE;
        }
    }

    public boolean isExpired()
    {
        synchronized (this)
        {
            // TODO review
            return _lifeCycleState == LifeCycleState.EXPIRE || _lifeCycleState == LifeCycleState.EXPIRING;
        }
    }

    public boolean isInitial()
    {
        synchronized (this)
        {
            return _initial;
        }
    }

    public boolean isSuspended()
    {
        synchronized (this)
        {
            return _state == State.WAITING || _state == State.HANDLING && _lifeCycleState == LifeCycleState.ASYNC;
        }
    }

    boolean isCompleted()
    {
        synchronized (this)
        {
            return _lifeCycleState == LifeCycleState.COMPLETED;
        }
    }

    public boolean isAsyncStarted()
    {
        synchronized (this)
        {
            if (_state == State.HANDLING)
                return _lifeCycleState != LifeCycleState.BLOCKING;
            return _lifeCycleState == LifeCycleState.ASYNC || _lifeCycleState == LifeCycleState.EXPIRING;
        }
    }

    public boolean isAsyncComplete()
    {
        synchronized (this)
        {
            return _lifeCycleState == LifeCycleState.COMPLETE;
        }
    }

    public boolean isAsync()
    {
        synchronized (this)
        {
            return !_initial || _lifeCycleState != LifeCycleState.BLOCKING;
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
        synchronized (this)
        {
            event = _event;
        }
        return getContextHandler(event);
    }

    ContextHandler getContextHandler(AsyncContextEvent event)
    {
        if (event != null)
        {
            Context context = ((Context)event.getServletContext());
            if (context != null)
                return context.getContextHandler();
        }
        return null;
    }

    public ServletResponse getServletResponse()
    {
        final AsyncContextEvent event;
        synchronized (this)
        {
            event = _event;
        }
        return getServletResponse(event);
    }

    public ServletResponse getServletResponse(AsyncContextEvent event)
    {
        if (event != null && event.getSuppliedResponse() != null)
            return event.getSuppliedResponse();
        return _channel.getResponse();
    }

    void runInContext(AsyncContextEvent event, Runnable runnable)
    {
        ContextHandler contextHandler = getContextHandler(event);
        if (contextHandler == null)
            runnable.run();
        else
            contextHandler.handle(_channel.getRequest(), runnable);
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
        _channel.getRequest().setAttribute(name, attribute);
    }

    /**
     * Called to signal async read isReady() has returned false.
     * This indicates that there is no content available to be consumed
     * and that once the channel enters the ASYNC_WAIT state it will
     * register for read interest by calling {@link HttpChannel#onAsyncWaitForContent()}
     * either from this method or from a subsequent call to {@link #unhandle()}.
     */
    public void onReadUnready()
    {
        boolean interested = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onReadUnready {}", toStringLocked());

            switch (_asyncReadState)
            {
                case IDLE:
                case READY:
                    if (_state == State.WAITING)
                    {
                        interested = true;
                        _asyncReadState = AsyncReadState.REGISTERED;
                    }
                    else
                    {
                        _asyncReadState = AsyncReadState.REGISTER;
                    }
                    break;

                case REGISTER:
                case REGISTERED:
                case POSSIBLE:
                case PRODUCING:
                    break;
            }
        }

        if (interested)
            _channel.onAsyncWaitForContent();
    }

    /**
     * Called to signal that content is now available to read.
     * If the channel is in ASYNC_WAIT state and unready (ie isReady() has
     * returned false), then the state is changed to ASYNC_WOKEN and true
     * is returned.
     *
     * @return True IFF the channel was unready and in ASYNC_WAIT state
     */
    public boolean onContentAdded()
    {
        boolean woken = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onContentAdded {}", toStringLocked());

            switch (_asyncReadState)
            {
                case IDLE:
                case READY:
                    break;

                case PRODUCING:
                    _asyncReadState = AsyncReadState.READY;
                    break;

                case REGISTER:
                case REGISTERED:
                    _asyncReadState = AsyncReadState.READY;
                    if (_state == State.WAITING)
                    {
                        woken = true;
                        _state = State.WAKING;
                    }
                    break;

                case POSSIBLE:
                    throw new IllegalStateException(toStringLocked());
            }
        }
        return woken;
    }

    /**
     * Called to signal that the channel is ready for a callback.
     * This is similar to calling {@link #onReadUnready()} followed by
     * {@link #onContentAdded()}, except that as content is already
     * available, read interest is never set.
     *
     * @return true if woken
     */
    public boolean onReadReady()
    {
        boolean woken = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onReadReady {}", toStringLocked());

            switch (_asyncReadState)
            {
                case IDLE:
                    _asyncReadState = AsyncReadState.READY;
                    if (_state == State.WAITING)
                    {
                        woken = true;
                        _state = State.WAKING;
                    }
                    break;

                default:
                    throw new IllegalStateException(toStringLocked());
            }
        }
        return woken;
    }

    /**
     * Called to indicate that more content may be available,
     * but that a handling thread may need to produce (fill/parse)
     * it.  Typically called by the async read success callback.
     *
     * @return <code>true</code> if more content may be available
     */
    public boolean onReadPossible()
    {
        boolean woken = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onReadPossible {}", toStringLocked());

            switch (_asyncReadState)
            {
                case REGISTERED:
                    _asyncReadState = AsyncReadState.POSSIBLE;
                    if (_state == State.WAITING)
                    {
                        woken = true;
                        _state = State.WAKING;
                    }
                    break;

                default:
                    throw new IllegalStateException(toStringLocked());
            }
        }
        return woken;
    }

    /**
     * Called to signal that a read has read -1.
     * Will wake if the read was called while in ASYNC_WAIT state
     *
     * @return <code>true</code> if woken
     */
    public boolean onReadEof()
    {
        boolean woken = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onEof {}", toStringLocked());

            // Force read ready so onAllDataRead can be called
            _asyncReadState = AsyncReadState.READY;
            if (_state == State.WAITING)
            {
                woken = true;
                _state = State.WAKING;
            }
        }
        return woken;
    }

    public boolean onWritePossible()
    {
        boolean wake = false;

        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onWritePossible {}", toStringLocked());

            _asyncWritePossible = true;
            if (_state == State.WAITING)
            {
                _state = State.WAKING;
                wake = true;
            }
        }

        return wake;
    }
}
