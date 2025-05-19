//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.servlet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static jakarta.servlet.RequestDispatcher.ERROR_EXCEPTION;
import static jakarta.servlet.RequestDispatcher.ERROR_EXCEPTION_TYPE;
import static jakarta.servlet.RequestDispatcher.ERROR_MESSAGE;
import static jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI;
import static jakarta.servlet.RequestDispatcher.ERROR_SERVLET_NAME;
import static jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE;

/**
 * holder of the state of request-response cycle.
 */
public class ServletChannelState
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletChannelState.class);

    private static final long DEFAULT_TIMEOUT = Long.getLong("%s.DEFAULT_TIMEOUT".formatted(ServletChannelState.class.getName()), 30000L);

    /*
     * The state of the ServletChannel,used to control the overall lifecycle.
     * <pre>
     *     IDLE <-----> HANDLING ----> WAITING
     *       |                 ^       /
     *       |                  \     /
     *       v                   \   v
     *    UPGRADED               WOKEN
     * </pre>
     */
    public enum State
    {
        IDLE,        // Idle request
        HANDLING,    // Request dispatched to filter/servlet or Async IO callback
        WAITING,     // Suspended and waiting
        WOKEN,       // Dispatch to handle from ASYNC_WAIT
        UPGRADED     // Request upgraded the connection
    }

    /*
     * The state of the request processing lifecycle.
     * <pre>
     *       ERRORING
     *       BLOCKING <----> COMPLETING ---> COMPLETED
     *       ^  |  ^            ^
     *      /   |   \           |
     *     |    |    DISPATCH   |
     *     |    |    ^  ^       |
     *     |    v   /   |       |
     *     |  ASYNC -------> COMPLETE
     *     |    |       |       ^
     *     |    v       |       |
     *     |  EXPIRE    |       |
     *      \   |      /        |
     *       \  v     /         |
     *       EXPIRING ----------+
     * </pre>
     */
    private enum RequestState
    {
        BLOCKING,    // Blocking request dispatched
        ERRORING,    // Request passed to ErrorHandler (may execute Servlets)
        ASYNC,       // AsyncContext.startAsync() has been called
        DISPATCH,    // AsyncContext.dispatch() has been called
        EXPIRE,      // AsyncContext timeout has happened
        EXPIRING,    // AsyncListeners are being called
        COMPLETE,    // AsyncContext.complete() has been called
        COMPLETING,  // Request is being closed (maybe asynchronously)
        COMPLETED    // Response is completed
    }

    /*
     * The input readiness state.
     * <pre>
     *              read() without preceding
     *              isReady()  ------
     *                         \     \   unhandle() returns Action.READ_CALLBACK to call the ReadListener,
     *                          \     \  or read() stole available content after setReadListener()
     *                           --> IDLE <--------------
     *     blocking read() unblocked  ^                  \
     *                                |                   \
     *                                |                    \  setReadListener() called while
     *            registering demand  v                     v content is available
     *                               UNREADY ------------> READY
     *                                         demand
     *                                         serviced
     * </pre>
     */
    private enum InputState
    {
        /**
         * The 'default' state, when there is no pending demand nor a pending notification to the ReadListener.
         * There are 3 ways to transition to this state:
         * <ul>
         *  <li>from IDLE: when an async read() is called without a preceding call to isReady()</li>
         *  <li>from READY: just before unhandle() returns Action.READ_CALLBACK to call read listener or
         *  when read() steals available content after setReadListener()</li>
         *  <li>from UNREADY: when a blocking read() got unblocked</li>
         * </ul>
         */
        IDLE,

        /**
         * The 'demand registered' state. There is only 1 way to transition to this state:
         * <ul>
         *  <li>from IDLE: when isReady() is called and there is no content available, so a demand is registered</li>
         * </ul>
         */
        UNREADY,

        /**
         * The 'dispatch a notification to the ReadListener' state. There are 2 ways to transition to this state:
         * <ul>
         *  <li>from IDLE: when setReadListener() is called while there is content available</li>
         *  <li>from UNREADY: when demand is serviced because content is now available</li>
         * </ul>
         */
        READY
    }

    /*
     * The output state.
     */
    private enum OutputState
    {
        IDLE,
        OPEN,
        COMPLETED,
        ABORTED,
    }

    /**
     * The actions to take as the channel moves from state to state.
     */
    public enum Action
    {
        DISPATCH,         // handle a normal request dispatch
        ASYNC_DISPATCH,   // handle an async request dispatch
        SEND_ERROR,       // Generate an error page or error dispatch
        ASYNC_ERROR,      // handle an async error
        ASYNC_TIMEOUT,    // call asyncContext onTimeout
        WRITE_CALLBACK,   // handle an IO write callback
        READ_CALLBACK,    // handle an IO read callback
        COMPLETE,         // Complete the response by closing output
        TERMINATED,       // No further actions
        WAIT,             // Wait for further events
    }

    private final AutoLock _lock = new AutoLock();
    private final ServletChannel _servletChannel;
    private List<AsyncListener> _asyncListeners;
    private State _state = State.IDLE;
    private RequestState _requestState = RequestState.BLOCKING;
    private OutputState _outputState = OutputState.IDLE;
    private InputState _inputState = InputState.IDLE;
    private boolean _initial = true;
    private boolean _sendError;
    private boolean _asyncWritePossible;
    private long _timeoutMs = DEFAULT_TIMEOUT;
    private AsyncContextEvent _event;
    private Thread _onTimeoutThread;
    private Throwable _failure;
    private boolean _failureListener;

    protected ServletChannelState(ServletChannel servletChannel)
    {
        _servletChannel = servletChannel;
    }

    public ServletChannel getServletChannel()
    {
        return _servletChannel;
    }

    public boolean isAborted()
    {
        try (AutoLock ignored = lock())
        {
            return _outputState == OutputState.ABORTED;
        }
    }

    public void openOutput()
    {
        try (AutoLock ignored = lock())
        {
            if (_outputState != OutputState.IDLE)
                throw new IllegalStateException(toStringLocked());
            _outputState = OutputState.OPEN;
        }
    }

    AutoLock lock()
    {
        return _lock.lock();
    }

    boolean isLockHeldByCurrentThread()
    {
        return _lock.isHeldByCurrentThread();
    }

    public State getState()
    {
        try (AutoLock ignored = lock())
        {
            return _state;
        }
    }

    public void addListener(AsyncListener listener)
    {
        try (AutoLock ignored = lock())
        {
            if (_asyncListeners == null)
                _asyncListeners = new ArrayList<>();
            _asyncListeners.add(listener);
        }
    }

    public boolean hasListener(AsyncListener listener)
    {
        try (AutoLock ignored = lock())
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
        try (AutoLock ignored = lock())
        {
            return _sendError;
        }
    }

    public void setTimeout(long ms)
    {
        try (AutoLock ignored = lock())
        {
            _timeoutMs = ms;
        }
    }

    public long getTimeout()
    {
        try (AutoLock ignored = lock())
        {
            return _timeoutMs;
        }
    }

    public AsyncContextEvent getAsyncContextEvent()
    {
        try (AutoLock ignored = lock())
        {
            return _event;
        }
    }

    @Override
    public String toString()
    {
        try (AutoLock ignored = lock())
        {
            return toStringLocked();
        }
    }

    private String toStringLocked()
    {
        return String.format("%s@%x{%s}",
            getClass().getSimpleName(),
            hashCode(),
            getStatusStringLocked());
    }

    private String getStatusStringLocked()
    {
        return String.format("s=%s rs=%s os=%s is=%s awp=%b se=%b i=%b al=%d",
            _state,
            _requestState,
            _outputState,
            _inputState,
            _asyncWritePossible,
            _sendError,
            _initial,
            _asyncListeners == null ? 0 : _asyncListeners.size());
    }

    public String getStatusString()
    {
        try (AutoLock ignored = lock())
        {
            return getStatusStringLocked();
        }
    }

    public Throwable completeResponse()
    {
        try (AutoLock ignored = lock())
        {
            // This method is called when the state machine
            // is about to terminate the processing, just
            // before completing the Handler's callback.
            assert _outputState == OutputState.OPEN || _failure != null;

            if (_outputState == OutputState.OPEN)
                _outputState = OutputState.COMPLETED;

            return _failure;
        }
    }

    public boolean isResponseCommitted()
    {
        return _servletChannel.getServletContextResponse().isCommitted();
    }

    public boolean isResponseCompleted()
    {
        try (AutoLock ignored = lock())
        {
            return _outputState == OutputState.COMPLETED;
        }
    }

    private boolean abortResponse(Throwable failure)
    {
        try (AutoLock ignored = lock())
        {
            switch (_outputState)
            {
                case COMPLETED:
                case ABORTED:
                    return false;

                default:
                    _outputState = OutputState.ABORTED;
                    _failure = failure;
                    return true;
            }
        }
    }

    public void abort(Throwable failure)
    {
        boolean handle = false;
        try (AutoLock ignored = lock())
        {
            boolean aborted = abortResponse(failure);
            if (LOG.isDebugEnabled())
                LOG.debug("abort={} {}", aborted, this, failure);
            if (aborted)
            {
                handle = _state == State.WAITING;
                if (handle)
                    _state = State.WOKEN;
                _requestState = RequestState.COMPLETED;
            }
        }
        if (handle)
            scheduleDispatch();
    }

    /**
     * @return Next handling of the request should proceed
     */
    public Action handling()
    {
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("handling {}", toStringLocked());

            switch (_state)
            {
                case IDLE:
                    if (_requestState != RequestState.BLOCKING)
                        throw new IllegalStateException(getStatusStringLocked());
                    _initial = true;
                    _state = State.HANDLING;
                    return Action.DISPATCH;

                case WOKEN:
                    if (_event != null && _event.getThrowable() != null && !_sendError)
                    {
                        _state = State.HANDLING;
                        return Action.ASYNC_ERROR;
                    }

                    Action action = nextAction(true);
                    if (LOG.isDebugEnabled())
                        LOG.debug("nextAction(true) {} {}", action, toStringLocked());
                    return action;

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
     * be handled again (e.g. because of a resume that happened before unhandle was called)
     */
    protected Action unhandle()
    {
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("unhandle {}", toStringLocked());

            if (_state != State.HANDLING)
                throw new IllegalStateException(this.getStatusStringLocked());

            _initial = false;

            Action action = nextAction(false);
            if (LOG.isDebugEnabled())
                LOG.debug("nextAction(false) {} {}", action, toStringLocked());
            return action;
        }
    }

    private Action nextAction(boolean handling)
    {
        // Assume we can keep going, but exceptions are below
        _state = State.HANDLING;

        if (_sendError)
        {
            switch (_requestState)
            {
                case BLOCKING:
                case ASYNC:
                case COMPLETE:
                case DISPATCH:
                case COMPLETING:
                    _requestState = RequestState.BLOCKING;
                    _sendError = false;
                    return Action.SEND_ERROR;

                default:
                    break;
            }
        }

        switch (_requestState)
        {
            case BLOCKING:
                if (handling)
                    throw new IllegalStateException(getStatusStringLocked());
                _requestState = RequestState.COMPLETING;
                return Action.COMPLETE;

            case ASYNC:
                switch (_inputState)
                {
                    case IDLE:
                    case UNREADY:
                        break;
                    case READY:
                        _inputState = InputState.IDLE;
                        return Action.READ_CALLBACK;

                    default:
                        throw new IllegalStateException(getStatusStringLocked());
                }

                if (_asyncWritePossible)
                {
                    _asyncWritePossible = false;
                    return Action.WRITE_CALLBACK;
                }

                Scheduler scheduler = _servletChannel.getServletContextRequest()
                    .getConnectionMetaData().getConnector().getScheduler();
                if (scheduler != null && _timeoutMs > 0 && !_event.hasTimeoutTask())
                    _event.setTimeoutTask(scheduler.schedule(_event, _timeoutMs, TimeUnit.MILLISECONDS));
                _state = State.WAITING;
                return Action.WAIT;

            case DISPATCH:
                _requestState = RequestState.BLOCKING;
                return Action.ASYNC_DISPATCH;

            case EXPIRE:
                _requestState = RequestState.EXPIRING;
                return Action.ASYNC_TIMEOUT;

            case EXPIRING:
                if (handling)
                    throw new IllegalStateException(getStatusStringLocked());
                sendError(HttpStatus.INTERNAL_SERVER_ERROR_500, "AsyncContext timeout");
                // handle sendError immediately
                _requestState = RequestState.BLOCKING;
                _sendError = false;
                return Action.SEND_ERROR;

            case COMPLETE:
                _requestState = RequestState.COMPLETING;
                return Action.COMPLETE;

            case ERRORING:
            case COMPLETING:
                _state = State.WAITING;
                return Action.WAIT;

            case COMPLETED:
                _state = State.IDLE;
                return Action.TERMINATED;

            default:
                throw new IllegalStateException(getStatusStringLocked());
        }
    }

    public void startAsync(AsyncContextEvent event)
    {
        final List<AsyncListener> lastAsyncListeners;

        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("startAsync {}", toStringLocked());
            if (_state != State.HANDLING || (_requestState != RequestState.BLOCKING && _requestState != RequestState.ERRORING))
                throw new IllegalStateException(this.getStatusStringLocked());

            if (!_failureListener)
            {
                _failureListener = true;
                _servletChannel.getRequest().addFailureListener(this::asyncError);
            }
            _requestState = RequestState.ASYNC;
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
                            LOG.warn("Async dispatch error", e);
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

    /**
     * Called when an asynchronous call to {@code ErrorHandler.handle()} is about to happen.
     *
     * @see #errorHandlingComplete(Throwable)
     */
    void errorHandling()
    {
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("errorHandling {}", toStringLocked());
            _requestState = RequestState.ERRORING;
        }
    }

    /**
     * Called when the {@code Callback} passed to {@code ErrorHandler.handle()} is completed.
     *
     * @param failure the failure reported by the error handling,
     * or {@code null} if there was no failure
     */
    void errorHandlingComplete(Throwable failure)
    {
        boolean handle;
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("errorHandlingComplete {}", toStringLocked(), failure);

            handle = _state == State.WAITING;
            if (handle)
                _state = State.WOKEN;

            // If there is a failure while trying to
            // handle a previous failure, just bail out.
            if (failure != null)
                abortResponse(failure);

            if (_requestState == RequestState.ERRORING)
                _requestState = RequestState.COMPLETE;
        }
        if (handle)
            scheduleDispatch();
    }

    public void dispatch(ServletContext context, String path)
    {
        boolean dispatch = false;
        AsyncContextEvent event;

        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("dispatch {} -> {}", toStringLocked(), path);

            switch (_requestState)
            {
                case ASYNC:
                    break;
                case EXPIRING:
                    if (Thread.currentThread() != _onTimeoutThread)
                        throw new IllegalStateException(this.getStatusStringLocked());
                    break;
                default:
                    throw new IllegalStateException(this.getStatusStringLocked());
            }

            if (context != null)
                _event.setDispatchContext(context);
            if (path != null)
                _event.setDispatchPath(path);

            if (_requestState == RequestState.ASYNC && _state == State.WAITING)
            {
                _state = State.WOKEN;
                dispatch = true;
            }
            _requestState = RequestState.DISPATCH;
            event = _event;
        }

        cancelTimeout(event);
        if (dispatch)
            scheduleDispatch();
    }

    protected void timeout()
    {
        boolean dispatch = false;
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Timeout {}", toStringLocked());

            if (_requestState != RequestState.ASYNC)
                return;
            _requestState = RequestState.EXPIRE;

            if (_state == State.WAITING)
            {
                _state = State.WOKEN;
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
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onTimeout {}", toStringLocked());
            if (_requestState != RequestState.EXPIRING || _state != State.HANDLING)
                throw new IllegalStateException(toStringLocked());
            event = _event;
            listeners = _asyncListeners;
            _onTimeoutThread = Thread.currentThread();
        }

        try
        {
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
                                if (LOG.isDebugEnabled())
                                    LOG.warn("{} while invoking onTimeout listener {}", x, listener, x);
                                else
                                    LOG.warn("{} while invoking onTimeout listener {}", x, listener);
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
        finally
        {
            try (AutoLock ignored = lock())
            {
                _onTimeoutThread = null;
            }
        }
    }

    public void complete()
    {
        boolean handle = false;
        AsyncContextEvent event;
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("complete {}", toStringLocked());

            event = _event;
            switch (_requestState)
            {
                case EXPIRING:
                    if (Thread.currentThread() != _onTimeoutThread)
                        throw new IllegalStateException(this.getStatusStringLocked());
                    _requestState = _sendError ? RequestState.BLOCKING : RequestState.COMPLETE;
                    break;

                case ASYNC:
                    _requestState = _sendError ? RequestState.BLOCKING : RequestState.COMPLETE;
                    break;

                case COMPLETE:
                    return;
                default:
                    throw new IllegalStateException(this.getStatusStringLocked());
            }
            if (_state == State.WAITING)
            {
                handle = true;
                _state = State.WOKEN;
            }
        }

        cancelTimeout(event);
        if (handle)
            runInContext(event, _servletChannel::handle);
    }

    /**
     * This method is called when a failure occurs asynchronously to normal handling.
     * If the request is async, we arrange for the exception to be thrown from the
     * normal handling loop and then actually handled by {@link #onError(Throwable)}
     * @param failure the error.
     */
    public void asyncError(Throwable failure)
    {
        AsyncContextEvent event = null;
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("asyncError {}", toStringLocked(), failure);

            if (_state == State.WAITING && _requestState == RequestState.ASYNC)
            {
                _state = State.WOKEN;
                _event.addThrowable(failure);
                event = _event;
            }
            else
            {
                if (!QuietException.isQuiet(failure))
                    LOG.warn(failure.toString());
                if (LOG.isDebugEnabled())
                    LOG.debug("Async error", failure);
            }
        }

        if (event != null)
        {
            cancelTimeout(event);
            runInContext(event, _servletChannel::handle);
        }
    }

    public boolean onIdleTimeout(TimeoutException timeout)
    {
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onIdleTimeout {}", getStatusStringLocked(), timeout);
            return _state == State.IDLE;
        }
    }

    protected boolean onError(Throwable th)
    {
        boolean committed = _servletChannel.isCommitted();
        final AsyncContextEvent asyncEvent;
        final List<AsyncListener> asyncListeners;
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onError {}", getStatusStringLocked(), th);

            // This can only be called from within the handle loop
            if (_state != State.HANDLING)
                throw new IllegalStateException(getStatusStringLocked());

            // If sendError has already been called, we can only handle one failure at a time!
            if (_sendError)
            {
                LOG.warn("onError not handled due to prior sendError() {}", getStatusStringLocked(), th);
                return false;
            }

            // Check async state to determine type of handling
            switch (_requestState)
            {
                case BLOCKING:
                {
                    // Handle the exception with a sendError.
                    if (committed)
                        return true;
                    sendError(th);
                    return false;
                }
                case DISPATCH: // Dispatch has already been called, but we ignore and handle exception below
                case COMPLETE: // Complete has already been called, but we ignore and handle exception below
                case ASYNC:
                {
                    if (_asyncListeners == null || _asyncListeners.isEmpty())
                        return false;
                    asyncEvent = _event;
                    asyncEvent.addThrowable(th);
                    asyncListeners = _asyncListeners;
                    break;
                }
                default:
                {
                    LOG.warn("onError not handled due to invalid requestState {}", getStatusStringLocked(), th);
                    return false;
                }
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
                    if (LOG.isDebugEnabled())
                        LOG.warn("{} while invoking onError listener {}", x, listener, x);
                    else
                        LOG.warn("{} while invoking onError listener {}", x, listener);
                }
            }
        });

        // check the actions of the listeners
        try (AutoLock ignored = lock())
        {
            if (_requestState == RequestState.ASYNC && !_sendError)
            {
                // The listeners did not invoke API methods and the
                // container must provide a default error dispatch.
                if (committed)
                    return true;
                sendError(th);
                return false;
            }
            else if (_requestState != RequestState.COMPLETE)
            {
                if (QuietException.isQuiet(th))
                    LOG.debug("unhandled in state {}", _requestState, th);
                else
                    LOG.warn("unhandled in state {}", _requestState, new IllegalStateException(th));
            }
            return committed;
        }
    }

    private void sendError(Throwable th)
    {
        // No sync as this is always called with lock held
        assert _lock.isHeldByCurrentThread();

        // Determine the actual details of the exception
        final Request request = _servletChannel.getServletContextRequest();
        final int code;
        final String message;
        Throwable cause = _servletChannel.unwrap(th, HttpException.class, UnavailableException.class);
        if (cause == null)
        {
            code = HttpStatus.INTERNAL_SERVER_ERROR_500;
            message = th.toString();
        }
        else if (cause instanceof HttpException httpException)
        {
            code = httpException.getCode();
            message = httpException.getReason();
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

        sendError(code, message);

        // No ISE, so good to modify request/state
        request.setAttribute(ERROR_EXCEPTION, th);
        request.setAttribute(ERROR_EXCEPTION_TYPE, th.getClass());

        // Set Jetty specific attributes.
        request.setAttribute(ErrorHandler.ERROR_EXCEPTION, th);

        // Ensure any async lifecycle is ended!
        _requestState = RequestState.BLOCKING;
    }

    public void sendError(int code, String message)
    {
        // This method is called by Response.sendError to organise for an error page to be generated when it is possible:
        //  + The response is reset and temporarily closed.
        //  + The details of the error are saved as request attributes
        //  + The _sendError boolean is set to true so that an ERROR_DISPATCH action will be generated:
        //       - after unhandle for sync
        //       - after both unhandle and complete for async

        ServletContextRequest servletContextRequest = _servletChannel.getServletContextRequest();
        HttpServletRequest httpServletRequest = servletContextRequest.getServletApiRequest();

        final Request request = _servletChannel.getServletContextRequest();
        final Response response = _servletChannel.getServletContextResponse();
        if (message == null)
            message = HttpStatus.getMessage(code);

        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("sendError {}", toStringLocked());

            if (_outputState != OutputState.OPEN)
                throw new IllegalStateException(_outputState.toString());

            switch (_state)
            {
                case HANDLING:
                case WOKEN:
                case WAITING:
                    break;
                default:
                    throw new IllegalStateException(getStatusStringLocked());
            }

            response.setStatus(code);
            servletContextRequest.errorClose();

            request.setAttribute(org.eclipse.jetty.ee10.servlet.ErrorHandler.ERROR_CONTEXT, servletContextRequest.getErrorContext());
            request.setAttribute(ERROR_REQUEST_URI, httpServletRequest.getRequestURI());
            request.setAttribute(ERROR_SERVLET_NAME, servletContextRequest.getServletName());
            request.setAttribute(ERROR_STATUS_CODE, code);
            request.setAttribute(ERROR_MESSAGE, message);

            // Set Jetty Specific Attributes.
            request.setAttribute(ErrorHandler.ERROR_CONTEXT, servletContextRequest.getServletContext());
            request.setAttribute(ErrorHandler.ERROR_MESSAGE, message);
            request.setAttribute(ErrorHandler.ERROR_STATUS, code);

            _sendError = true;
            if (_event != null)
            {
                Throwable cause = (Throwable)request.getAttribute(ERROR_EXCEPTION);
                if (cause != null)
                    _event.addThrowable(cause);
            }
        }
    }

    protected void completing()
    {
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("completing {}", toStringLocked());

            if (_requestState == RequestState.COMPLETED)
                throw new IllegalStateException(getStatusStringLocked());
            _requestState = RequestState.COMPLETING;
        }
    }

    protected void completed(Throwable failure)
    {
        final List<AsyncListener> aListeners;
        final AsyncContextEvent event;
        boolean handle = false;

        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("completed {}", toStringLocked());

            if (_requestState != RequestState.COMPLETING)
                failure = ExceptionUtil.combine(failure, new IllegalStateException(getStatusStringLocked()));

            if (failure != null)
                abortResponse(failure);

            if (_event == null)
            {
                _requestState = RequestState.COMPLETED;
                aListeners = null;
                event = null;
                if (_state == State.WAITING)
                {
                    _state = State.WOKEN;
                    handle = true;
                }
            }
            else
            {
                aListeners = _asyncListeners;
                event = _event;
            }
        }

        // release any aggregate buffer from a closing flush
        _servletChannel.getHttpOutput().completed(failure);

        if (event != null)
        {
            cancelTimeout(event);
            if (aListeners != null)
            {
                runInContext(event, () ->
                {
                    for (AsyncListener listener : aListeners)
                    {
                        try
                        {
                            listener.onComplete(event);
                        }
                        catch (Throwable x)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.warn("{} while invoking onComplete listener {}", x, listener, x);
                            else
                                LOG.warn("{} while invoking onComplete listener {}", x, listener);
                        }
                    }
                });
            }
            event.completed();

            try (AutoLock ignored = lock())
            {
                _requestState = RequestState.COMPLETED;
                if (_state == State.WAITING)
                {
                    _state = State.WOKEN;
                    handle = true;
                }
            }
        }

        if (handle)
            _servletChannel.handle();
    }

    protected void recycle()
    {
        cancelTimeout();
        try (AutoLock ignored = lock())
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
            _requestState = RequestState.BLOCKING;
            if (_outputState != OutputState.ABORTED)
                _outputState = OutputState.IDLE;
            _initial = true;
            _inputState = InputState.IDLE;
            _asyncWritePossible = false;
            _timeoutMs = DEFAULT_TIMEOUT;
            _event = null;
            _failureListener = false;
        }
    }

    public void upgrade()
    {
        cancelTimeout();
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("upgrade {}", toStringLocked());

            if (_state != State.IDLE)
                throw new IllegalStateException(getStatusStringLocked());
            if (_inputState != InputState.IDLE)
                throw new IllegalStateException(getStatusStringLocked());
            _asyncListeners = null;
            _state = State.UPGRADED;
            _requestState = RequestState.BLOCKING;
            _initial = true;
            _asyncWritePossible = false;
            _timeoutMs = DEFAULT_TIMEOUT;
            _event = null;
        }
    }

    protected void scheduleDispatch()
    {
        _servletChannel.execute(_servletChannel::handle, _servletChannel.getRequest());
    }

    protected void cancelTimeout()
    {
        cancelTimeout(getAsyncContextEvent());
    }

    protected void cancelTimeout(AsyncContextEvent event)
    {
        if (event != null)
            event.cancelTimeoutTask();
    }

    public boolean isIdle()
    {
        try (AutoLock ignored = lock())
        {
            return _state == State.IDLE;
        }
    }

    public boolean isExpired()
    {
        try (AutoLock ignored = lock())
        {
            // TODO review
            return _requestState == RequestState.EXPIRE || _requestState == RequestState.EXPIRING;
        }
    }

    public boolean isInitial()
    {
        try (AutoLock ignored = lock())
        {
            return _initial;
        }
    }

    public boolean isSuspended()
    {
        try (AutoLock ignored = lock())
        {
            return _state == State.WAITING || _state == State.HANDLING && _requestState == RequestState.ASYNC;
        }
    }

    boolean isCompleted()
    {
        try (AutoLock ignored = lock())
        {
            return _requestState == RequestState.COMPLETED;
        }
    }

    public boolean isAsyncStarted()
    {
        try (AutoLock ignored = lock())
        {
            if (_state == State.HANDLING)
                return _requestState != RequestState.BLOCKING && _requestState != RequestState.ERRORING;
            return _requestState == RequestState.ASYNC || _requestState == RequestState.EXPIRING;
        }
    }

    public boolean isAsync()
    {
        try (AutoLock ignored = lock())
        {
            return !_initial || (_requestState != RequestState.BLOCKING && _requestState != RequestState.ERRORING);
        }
    }

    public ServletContextHandler getContextHandler()
    {
        return _servletChannel.getServletContextHandler();
    }

    void runInContext(AsyncContextEvent event, Runnable runnable)
    {
        event.getContext().run(runnable);
    }

    public Object getAttribute(String name)
    {
        return _servletChannel.getServletContextRequest().getAttribute(name);
    }

    public void removeAttribute(String name)
    {
        _servletChannel.getServletContextRequest().removeAttribute(name);
    }

    public void setAttribute(String name, Object attribute)
    {
        _servletChannel.getServletContextRequest().setAttribute(name, attribute);
    }

    /**
     * Called to signal that the channel is ready for a callback.
     *
     * @return true if woken
     */
    public boolean onReadReady()
    {
        boolean woken = false;
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onReadReady {}", toStringLocked());

            switch (_inputState)
            {
                case READY:
                    break;
                case IDLE:
                case UNREADY:
                    _inputState = InputState.READY;
                    if (_state == State.WAITING)
                    {
                        woken = true;
                        _state = State.WOKEN;
                    }
                    break;

                default:
                    throw new IllegalStateException(toStringLocked());
            }
        }
        return woken;
    }

    public boolean onReadListenerReady()
    {
        boolean woken = false;
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onReadListenerReady {}", toStringLocked());

            switch (_inputState)
            {
                case IDLE:
                    _inputState = InputState.READY;
                    if (_state == State.WAITING)
                    {
                        woken = true;
                        _state = State.WOKEN;
                    }
                    break;

                case READY:
                case UNREADY:
                default:
                    throw new IllegalStateException(toStringLocked());
            }
        }
        return woken;
    }

    /**
     * Called to indicate that the content is being consumed.
     */
    public void onReadIdle()
    {
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onReadIdle {}", toStringLocked());

            switch (_inputState)
            {
                case UNREADY:
                case READY:
                case IDLE:
                    _inputState = InputState.IDLE;
                    break;

                default:
                    throw new IllegalStateException(toStringLocked());
            }
        }
    }

    /**
     * Called to indicate that no content is currently available,
     * more content has been demanded and may be available, but
     * that a handling thread may need to produce (fill/parse) it.
     */
    public void onReadUnready()
    {
        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onReadUnready {}", toStringLocked());

            switch (_inputState)
            {
                case IDLE:
                    _inputState = InputState.UNREADY;
                    break;

                case READY:
                case UNREADY:
                default:
                    throw new IllegalStateException(toStringLocked());
            }
        }
    }

    public boolean isInputUnready()
    {
        try (AutoLock ignored = lock())
        {
            return _inputState == InputState.UNREADY;
        }
    }

    public boolean onWritePossible()
    {
        boolean wake = false;

        try (AutoLock ignored = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onWritePossible {}", toStringLocked());

            _asyncWritePossible = true;
            if (_state == State.WAITING)
            {
                _state = State.WOKEN;
                wake = true;
            }
        }

        return wake;
    }
}
