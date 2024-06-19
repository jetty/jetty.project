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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.util.Objects;

import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This specialized callback implements a pattern that allows
 * a large asynchronous task to be broken into smaller
 * asynchronous sub-tasks using iteration rather than recursion.
 * <p>
 * A typical example is the write of a large content to a socket,
 * divided in chunks. Chunk C1 is written by thread T1, which
 * also invokes the callback, which writes chunk C2, which invokes
 * the callback again, which writes chunk C3, and so forth.
 * <p>
 * The problem with the example above is that if the callback thread
 * is the same that performs the I/O operation, then the process
 * is recursive and may result in a stack overflow.
 * To avoid the stack overflow, a thread dispatch must be performed,
 * causing context switching and cache misses, affecting performance.
 * <p>
 * To avoid this issue, this callback atomically records whether
 * the callback for an asynchronous sub-task has been called
 * during the processing of the asynchronous sub-task, and if so
 * then the processing of the large asynchronous task iterates
 * rather than recursing.
 * <p>
 * Subclasses must implement method {@link #process()} where the
 * asynchronous sub-task is initiated and a suitable {@link Action}
 * is returned to this callback to indicate the overall progress ofk
 * the large asynchronous task.
 * This callback is passed to the asynchronous sub-task, and a call
 * to {@link #succeeded()} on this callback represents the successful
 * completion of the asynchronous sub-task, while a call to
 * {@link #failed(Throwable)} on this callback represents the
 * completion with a failure of the large asynchronous task.
 */
public abstract class IteratingCallback implements Callback
{
    private static final Logger LOG = LoggerFactory.getLogger(IteratingCallback.class);

    /**
     * The internal states of this callback.
     */
    enum State
    {
        /**
         * This callback is idle, ready to iterate.
         */
        IDLE,

        /**
         * This callback is just about to call {@link #process()},
         * or within it, or just exited from it, either normally
         * or by throwing. Further actions are waiting for the
         * {@link #process()} method to return.
         */
        PROCESSING,

        /**
         * The asynchronous sub-task was completed either with
         * a call to {@link #succeeded()} or {@link #failed(Throwable)}, whilst in
         * {@link #PROCESSING} state.  Further actions are waiting for the
         * {@link #process()} method to return.
         */
        PROCESSING_CALLED,

        /**
         * Method {@link #process()} returned {@link Action#SCHEDULED}
         * and this callback is waiting for the asynchronous sub-task
         * to complete via a callback to {@link #succeeded()} or {@link #failed(Throwable)}
         */
        PENDING,

        /**
         * This callback is complete.
         */
        COMPLETE,

        /**
         * Complete and can't be reset.
         */
        CLOSED
    }

    /**
     * The indication of the overall progress of the iteration
     * that implementations of {@link #process()} must return.
     */
    protected enum Action
    {
        /**
         * Indicates that {@link #process()} has no more work to do,
         * but the iteration is not completed yet, probably waiting
         * for additional events to trigger more work.
         */
        IDLE,

        /**
         * Indicates that {@link #process()} has initiated an asynchronous
         * sub-task, where the execution has started but the callback
         * that signals the completion of the asynchronous sub-task
         * may have not yet been invoked.
         */
        SCHEDULED,

        /**
         * Indicates that {@link #process()} has completed the whole
         * iteration successfully.
         */
        SUCCEEDED
    }

    private final AutoLock _lock = new AutoLock();
    private State _state;
    private Throwable _failure;
    private boolean _reprocess;
    private boolean _aborted;

    protected IteratingCallback()
    {
        _state = State.IDLE;
    }

    protected IteratingCallback(boolean needReset)
    {
        _state = needReset ? State.COMPLETE : State.IDLE;
    }

    /**
     * Method called by {@link #iterate()} to process the asynchronous sub-task.
     * <p>
     * Implementations must initiate the asynchronous execution of the sub-task
     * (if any) and return an appropriate action:
     * <ul>
     * <li>{@link Action#IDLE} when no sub tasks are available for execution
     * but the overall job is not completed yet</li>
     * <li>{@link Action#SCHEDULED} when the sub task asynchronous execution
     * has been started</li>
     * <li>{@link Action#SUCCEEDED} when the overall job is completed</li>
     * </ul>
     *
     * @return the appropriate Action
     * @throws Throwable if the sub-task processing throws
     */
    protected abstract Action process() throws Throwable;

    /**
     * Invoked when the overall task has completed successfully.
     *
     * @see #onCompleteFailure(Throwable)
     */
    protected void onCompleteSuccess()
    {
    }

    /**
     * Invoked when the overall task has completed with a failure.
     *
     * @param cause the throwable to indicate cause of failure
     * @see #onCompleteSuccess()
     */
    protected void onCompleteFailure(Throwable cause)
    {
    }

    protected void onAborted(Throwable cause)
    {
    }

    protected void onCompleted(Throwable causeOrNull)
    {
        if (causeOrNull == null)
            onCompleteSuccess();
        else
            onCompleteFailure(causeOrNull);
    }

    private void doCompleteSuccess()
    {
        onCompleted(null);
    }

    private void doCompleteFailure(Throwable cause)
    {
        onCompleted(cause);
    }

    /**
     * This method must be invoked by applications to start the processing
     * of asynchronous sub-tasks.
     * <p>
     * It can be called at any time by any thread, and its contract is that
     * when called, then the {@link #process()} method will be called during
     * or soon after, either by the calling thread or by another thread, but
     * in either case by one thread only.
     */
    public void iterate()
    {
        boolean process = false;

        try (AutoLock ignored = _lock.lock())
        {
            switch (_state)
            {
                case IDLE:
                    _state = State.PROCESSING;
                    process = true;
                    break;

                case PROCESSING:
                case PROCESSING_CALLED:
                    _reprocess = true;
                    break;

                default:
                    break;
            }
        }
        if (process)
            processing();
    }

    private void processing()
    {
        // This should only ever be called when in processing state, however a failed or close call
        // may happen concurrently, so state is not assumed.

        boolean doCompleteSuccess = false;
        Throwable onAbortDoCompleteFailure = null;
        Throwable doCompleteFailure = null;
        Throwable onAbort = null;

        // While we are processing
        processing:
        while (true)
        {
            // Call process to get the action that we have to take.
            Action action;
            try
            {
                action = process();
            }
            catch (Throwable x)
            {
                action = null;
                failed(x);
                // Fall through to possibly invoke onCompleteFailure().
            }

            // acted on the action we have just received
            try (AutoLock ignored = _lock.lock())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("processing {} {}", action, this);

                switch (_state)
                {
                    case PROCESSING:
                    {
                        if (action == null)
                            break processing;
                        switch (action)
                        {
                            case IDLE:
                            {
                                if (_aborted)
                                {
                                    _state = _failure instanceof ClosedException ? State.CLOSED : State.COMPLETE;
                                    onAbortDoCompleteFailure = _failure;
                                    break processing;
                                }

                                // Has iterate been called while we were processing?
                                if (_reprocess)
                                {
                                    // yes, so skip idle and keep processing
                                    _reprocess = false;
                                    continue;
                                }

                                // No, so we can go idle
                                _state = State.IDLE;
                                break processing;
                            }
                            case SCHEDULED:
                            {
                                // we won the race against the callback, so the callback has to process and we can break processing
                                _state = State.PENDING;
                                if (_aborted)
                                {
                                    onAbort = _failure;
                                    _failure = new AbortingException(onAbort);
                                }
                                break processing;
                            }
                            case SUCCEEDED:
                            {
                                // we lost the race against the callback,
                                _reprocess = false;
                                if (_aborted)
                                {
                                    _state = _failure instanceof ClosedException ? State.CLOSED : State.COMPLETE;
                                    onAbortDoCompleteFailure = _failure;
                                }
                                else
                                {
                                    _state = State.COMPLETE;
                                    doCompleteSuccess = true;
                                }
                                break processing;
                            }
                            default:
                            {
                                break;
                            }
                        }
                        throw new IllegalStateException(String.format("%s[action=%s]", this, action));
                    }

                    case PROCESSING_CALLED:
                    {
                        if (action != Action.SCHEDULED && action != null)
                            LOG.warn("Processing called when not schedules {}", action);
                        if (_failure != null)
                        {
                            if (_aborted)
                                onAbortDoCompleteFailure = _failure;
                            else
                                doCompleteFailure = _failure;
                            _state = _failure instanceof ClosedException ? State.CLOSED : State.COMPLETE;
                            break processing;
                        }
                        _state = State.PROCESSING;
                        break;
                    }

                    default:
                        throw new IllegalStateException(String.format("%s[action=%s]", this, action));
                }
            }
        }
        if (onAbortDoCompleteFailure != null)
            ExceptionUtil.callAndThen(onAbortDoCompleteFailure, this::onAborted, this::doCompleteFailure);
        else if (doCompleteSuccess)
            doCompleteSuccess();
        else if (doCompleteFailure != null)
            doCompleteFailure(doCompleteFailure);
        else if (onAbort != null)
            ExceptionUtil.callAndThen(onAbort, this::onAborted, this::doAbortPendingCompletion);
    }

    /**
     * Method to invoke when the asynchronous sub-task succeeds.
     * <p>
     * Subclasses that override this method must always remember
     * to call {@code super.succeeded()}.
     */
    @Override
    public void succeeded()
    {
        boolean process = false;
        Throwable doCompleteFailure = null;
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("succeeded {}", this);
            switch (_state)
            {
                case PROCESSING:
                {
                    // Another thread is processing, so we just tell it the state
                    _state = State.PROCESSING_CALLED;
                    break;
                }
                case PENDING:
                {
                    if (_aborted)
                    {
                        if (_failure instanceof AbortingException)
                        {
                            // Another thread is still calling onAborted, so we will let it do the completion
                            _state = _failure.getCause() instanceof ClosedException ? State.CLOSED : State.COMPLETE;
                        }
                        else
                        {
                            // The onAborted call is complete, so we must do the completion
                            _state = _failure instanceof ClosedException ? State.CLOSED : State.COMPLETE;
                            doCompleteFailure = _failure;
                        }
                    }
                    else
                    {
                        // No other thread is processing, so we will do the processing
                        _state = State.PROCESSING;
                        process = true;
                    }
                    break;
                }
                case COMPLETE, CLOSED:
                {
                    // Too late
                    return;
                }
                default:
                {
                    throw new IllegalStateException(toString());
                }
            }
        }
        if (process)
            processing();
        else if (doCompleteFailure != null)
            doCompleteFailure(doCompleteFailure);
    }

    /**
     * Method to invoke when the asynchronous sub-task fails,
     * or to fail the overall asynchronous task and therefore
     * terminate the iteration.
     * <p>
     * Subclasses that override this method must always remember
     * to call {@code super.failed(Throwable)}.
     * <p>
     * Eventually, {@link #onCompleteFailure(Throwable)} is
     * called, either by the caller thread or by the processing
     * thread.
     *
     * @see #isFailed()
     */
    @Override
    public void failed(Throwable cause)
    {
        cause = Objects.requireNonNullElseGet(cause, IOException::new);

        Throwable doCompleteFailure = null;
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failed {}", this, cause);
            switch (_state)
            {
                case PROCESSING:
                {
                    // Another thread is processing, so we just tell it the state
                    _state = State.PROCESSING_CALLED;
                    if (_failure == null)
                        _failure = cause;
                    else
                        ExceptionUtil.addSuppressedIfNotAssociated(_failure, cause);
                    break;
                }
                case PENDING:
                {
                    if (_aborted)
                    {
                        if (_failure instanceof AbortingException)
                        {
                            // Another thread is still calling onAborted, so we will let it do the completion
                            ExceptionUtil.addSuppressedIfNotAssociated(_failure.getCause(), cause);
                            _state = _failure.getCause() instanceof ClosedException ? State.CLOSED : State.COMPLETE;
                        }
                        else
                        {
                            // The onAborted call is complete, so we must do the completion
                            ExceptionUtil.addSuppressedIfNotAssociated(_failure, cause);
                            _state = _failure instanceof ClosedException ? State.CLOSED : State.COMPLETE;
                            doCompleteFailure = _failure;
                        }
                    }
                    else
                    {
                        // No other thread is processing, so we will do the processing
                        _state = State.COMPLETE;
                        _failure = cause;
                        doCompleteFailure = _failure;
                    }
                    break;
                }
                case COMPLETE, CLOSED:
                {
                    // Too late
                    ExceptionUtil.addSuppressedIfNotAssociated(_failure, cause);
                    return;
                }
                default:
                {
                    throw new IllegalStateException(toString());
                }
            }
        }
        if (doCompleteFailure != null)
            doCompleteFailure(doCompleteFailure);
    }

    /**
     * Method to invoke to forbid further invocations to {@link #iterate()}
     * and {@link #reset()}.
     * <p>
     * When this method is invoked during processing, it behaves like invoking
     * {@link #failed(Throwable)}.
     *
     * @see #isClosed()
     */
    public void close()
    {
        Throwable onAbort = null;
        Throwable onAbortDoCompleteFailure = null;

        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("close {}", this);
            switch (_state)
            {
                case IDLE ->
                {
                    // Nothing happening so we can abort and complete
                    _state = State.CLOSED;
                    _failure = new ClosedException();
                    onAbortDoCompleteFailure = _failure;
                }
                case PROCESSING, PROCESSING_CALLED ->
                {
                    // Another thread is processing, so we just tell it the state and let it handle it
                    if (_aborted)
                    {
                        ExceptionUtil.addSuppressedIfNotAssociated(_failure, new ClosedException());
                    }
                    else
                    {
                        _aborted = true;
                        _failure = new ClosedException();
                    }
                }

                case PENDING ->
                {
                    // We are waiting for the callback, so we can only call onAbort and then keep waiting
                    onAbort = new ClosedException();
                    _failure = new AbortingException(onAbort);
                    _aborted = true;
                }

                case COMPLETE ->
                {
                    _state = State.CLOSED;
                }

                case CLOSED ->
                {
                    // too late
                    return;
                }
            }
        }

        if (onAbort != null)
            ExceptionUtil.callAndThen(onAbort, this::onAborted, this::doAbortPendingCompletion);
        else if (onAbortDoCompleteFailure != null)
            ExceptionUtil.callAndThen(onAbortDoCompleteFailure, this::onAborted, this::doCompleteFailure);
    }

    /**
     * <p>Method to invoke to stop further processing iterations.</p>
     * <p>This method causes {@link #onCompleteFailure(Throwable)} to
     * ultimately be invoked, either during this call or later after
     * any call to {@link #process()} has returned.</p>
     *
     * @param cause the cause of the abort
     * @return {@code true} if abort was called before the callback was complete.
     * @see #isAborted()
     */
    public boolean abort(Throwable cause)
    {
        cause = Objects.requireNonNullElseGet(cause, Throwable::new);

        boolean onAbort = false;
        boolean onAbortDoCompleteFailure = false;
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("abort {}", this, cause);

            // Are we already aborted?
            if (_aborted)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(_failure, cause);
                return false;
            }

            switch (_state)
            {
                case IDLE:
                {
                    // Nothing happening so we can abort and complete
                    _state = State.COMPLETE;
                    _failure = cause;
                    _aborted = true;
                    onAbortDoCompleteFailure = true;
                    break;
                }

                case PROCESSING:
                {
                    // Another thread is processing, so we just tell it the state and let it handle everything
                    _failure = cause;
                    _aborted = true;
                    break;
                }

                case PROCESSING_CALLED:
                {
                    // Another thread is processing, but we have already succeeded or failed.
                    if (_failure == null)
                        _failure = cause;
                    else
                        ExceptionUtil.addSuppressedIfNotAssociated(_failure, cause);
                    _aborted = true;
                    break;
                }

                case PENDING:
                {
                    // We are waiting for the callback, so we can only call onAbort and then keep waiting
                    onAbort = true;
                    _failure = new AbortingException(cause);
                    _aborted = true;
                    break;
                }

                case COMPLETE, CLOSED:
                {
                    // too late
                    ExceptionUtil.addSuppressedIfNotAssociated(_failure, cause);
                    return false;
                }
            }
        }

        if (onAbortDoCompleteFailure)
            ExceptionUtil.callAndThen(cause, this::onAborted, this::doCompleteFailure);
        else if (onAbort)
            ExceptionUtil.callAndThen(cause, this::onAborted, this::doAbortPendingCompletion);

        return true;
    }

    private void doAbortPendingCompletion()
    {
        Throwable doCompleteFailure = null;
        try (AutoLock ignored = _lock.lock())
        {
            _failure = _failure.getCause();

            if (Objects.requireNonNull(_state) != State.PENDING)
            {
                // the callback completed, one way or another, so it is up to use to do the completion
                doCompleteFailure = _failure;
            }
        }

        if (doCompleteFailure != null)
            ExceptionUtil.call(doCompleteFailure, this::doCompleteFailure);
    }

    /**
     * @return whether this callback is idle, and {@link #iterate()} needs to be called
     */
    boolean isIdle()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _state == State.IDLE;
        }
    }

    /**
     * @return whether this callback has been {@link #close() closed}
     */
    public boolean isClosed()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _state == State.CLOSED || _failure instanceof ClosedException;
        }
    }

    /**
     * @return whether this callback has been {@link #failed(Throwable) failed}
     */
    public boolean isFailed()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _failure != null;
        }
    }

    /**
     * @return whether this callback and the overall asynchronous task has been succeeded
     *
     * @see #onCompleteSuccess()
     */
    public boolean isSucceeded()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _state == State.COMPLETE && _failure == null;
        }
    }

    /**
     * @return whether this callback has been {@link #abort(Throwable) aborted}
     */
    public boolean isAborted()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _aborted;
        }
    }

    /**
     * Resets this callback.
     * <p>
     * A callback can only be reset to the idle state from the
     * {@link #isSucceeded() succeeded} or {@link #isFailed() failed} states
     * or if it is already idle.
     *
     * @return true if the reset was successful
     */
    public boolean reset()
    {
        try (AutoLock ignored = _lock.lock())
        {
            switch (_state)
            {
                case IDLE:
                    return true;

                case COMPLETE:
                    _state = State.IDLE;
                    _failure = null;
                    _reprocess = false;
                    return true;

                default:
                    return false;
            }
        }
    }

    @Override
    public String toString()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return String.format("%s@%x[%s, %b, %s]", getClass().getSimpleName(), hashCode(), _state, _aborted, _failure);
        }
    }

    private static class ClosedException extends Exception
    {
        ClosedException()
        {
            super("Closed");
        }

        ClosedException(Throwable suppressed)
        {
            this();
            ExceptionUtil.addSuppressedIfNotAssociated(this, suppressed);
        }
    }

    private static class AbortingException extends Exception
    {
        AbortingException(Throwable cause)
        {
            super(cause.getMessage(), cause);
        }
    }
}
