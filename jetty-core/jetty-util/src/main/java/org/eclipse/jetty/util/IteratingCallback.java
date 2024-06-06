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
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

import org.eclipse.jetty.util.thread.AutoLock;

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
 * is returned to this callback to indicate the overall progress of
 * the large asynchronous task.
 * This callback is passed to the asynchronous sub-task, and a call
 * to {@link #succeeded()} on this callback represents the successful
 * completion of the asynchronous sub-task, while a call to
 * {@link #failed(Throwable)} on this callback represents the
 * completion with a failure of the large asynchronous task.
 */
public abstract class IteratingCallback implements Callback
{
    /**
     * The internal states of this callback.
     */
    private enum State
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
         * The was {@link #abort(Throwable) aborted} whilst in
         * {@link #PROCESSING} state. Further actions are waiting for the
         * {@link #process()} method to return.
         */
        PROCESSING_ABORT,

        /**
         * The was {@link #abort(Throwable) aborted} and the sub-task was completed either with
         * a call to {@link #succeeded()} or {@link #failed(Throwable)},whilst in
         * {@link #PROCESSING} state. Further actions are waiting for the
         * {@link #process()} method to return.
         */
        PROCESSING_CALLED_ABORT,

        /**
         * Method {@link #process()} returned {@link Action#SCHEDULED}
         * and this callback is waiting for the asynchronous sub-task
         * to complete via a callback to {@link #succeeded()} or {@link #failed(Throwable)}
         */
        PENDING,

        /**
         * The {@link #abort(Throwable)} method has been called and {@link Action#SCHEDULED} has
         * been returned from {@link #process()}. Further actions are waiting for a callback to
         * {@link #succeeded()} or {@link #failed(Throwable)}
         */
        PENDING_ABORT,

        /**
         * This callback is complete.
         */
        COMPLETE
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

    protected void onAbort(Throwable cause)
    {
    }

    protected void onCompleted()
    {
    }

    private void doCompleteSuccess()
    {
        try
        {
            onCompleteSuccess();
        }
        finally
        {
            onCompleted();
        }
    }

    private void doCompleteFailure(Throwable cause)
    {
        ExceptionUtil.call(cause, this::onCompleteFailure, this::onCompleted);
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
                case PROCESSING_ABORT:
                case PROCESSING_CALLED_ABORT:
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
        Throwable onAbortScheduled = null;

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
                                break processing;
                            }
                            case SUCCEEDED:
                            {
                                // we lost the race against the callback,
                                _reprocess = false;
                                _state = State.COMPLETE;
                                doCompleteSuccess = true;
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
                            throw new IllegalStateException(String.format("%s[action=%s]", this, action));
                        if (_failure != null)
                        {
                            doCompleteFailure = _failure;
                            _state = State.COMPLETE;
                            break processing;
                        }
                        _state = State.PROCESSING;
                        break;
                    }

                    case PROCESSING_ABORT:
                    {
                        if (action == null)
                            break processing;
                        switch (action)
                        {
                            case IDLE:
                            case SUCCEEDED:
                                _state = State.COMPLETE;
                                doCompleteFailure = _failure;
                                break processing;

                            case SCHEDULED:
                                onAbortScheduled = _failure;
                                break processing;
                        }
                        break;
                    }

                    case PROCESSING_CALLED_ABORT:
                    {
                        onAbortDoCompleteFailure = _failure;
                        break;
                    }

                    default:
                        throw new IllegalStateException(String.format("%s[action=%s]", this, action));
                }
            }
        }
        if (onAbortDoCompleteFailure != null)
            ExceptionUtil.call(onAbortDoCompleteFailure, this::onAbort, this::doCompleteFailure);
        else if (doCompleteSuccess)
            doCompleteSuccess();
        else if (doCompleteFailure != null)
            doCompleteFailure(doCompleteFailure);
        else if (onAbortScheduled != null)
            ExceptionUtil.call(onAbortScheduled, this::onAbort, this::onAbortScheduledProcessing);
    }

    private void onAbortScheduledProcessing()
    {
        Throwable doCompleteFailure = null;
        try (AutoLock ignored = _lock.lock())
        {
            switch (_state)
            {
                case PROCESSING_ABORT:
                {
                    _state = State.PENDING_ABORT;
                    break;
                }
                case PROCESSING_CALLED_ABORT:
                {
                    _state = State.COMPLETE;
                    doCompleteFailure = _failure;
                    break;
                }
                default:
                    throw new IllegalStateException(String.format("%s[action=onAbortScheduledProcessing]", this));
            }
        }
        if (doCompleteFailure != null)
            doCompleteFailure(doCompleteFailure);
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
            switch (_state)
            {
                case PROCESSING:
                {
                    // Another thread is processing, so we just tell it the state
                    _state = State.PROCESSING_CALLED;
                    break;
                }
                case PROCESSING_ABORT:
                {
                    // another thread is processing, so it will handle everything, we just tell it the state
                    _state = State.PROCESSING_CALLED_ABORT;
                    break;
                }
                case PENDING:
                {
                    // No other thread is processing, so we will do it
                    _state = State.PROCESSING;
                    process = true;
                    break;
                }
                case PENDING_ABORT:
                {
                    // onAbort has already been called, so we just need to complete the failure
                    _state = State.COMPLETE;
                    doCompleteFailure = _failure;
                    break;
                }
                case COMPLETE:
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

        boolean doCompleteFailure = false;
        try (AutoLock ignored = _lock.lock())
        {
            switch (_state)
            {
                case PROCESSING:
                {
                    // Another thread is processing, so we just tell it the state
                    _state = State.PROCESSING_CALLED;
                    break;
                }
                case PROCESSING_ABORT:
                {
                    // another thread is processing, so it will handle everything, we just tell it the state
                    _state = State.PROCESSING_CALLED_ABORT;
                    break;
                }
                case PENDING:
                case PENDING_ABORT:
                {
                    // No other thread is processing, so we will do it
                    _state = State.COMPLETE;
                    doCompleteFailure = true;
                    break;
                }
                case COMPLETE:
                {
                    // too late!
                    return;
                }
                default:
                    throw new IllegalStateException(toString());
            }

            if (_failure == null)
                _failure = cause;
            else
                ExceptionUtil.addSuppressedIfNotAssociated(_failure, cause);
            cause = _failure;

        }
        if (doCompleteFailure)
            doCompleteFailure(cause);
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
        // TODO avoid exception if we are IDLE or COMPLETE
        abort(new ClosedChannelException());
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

        boolean doAbort = false;
        boolean doCompleteFailure = false;
        try (AutoLock ignored = _lock.lock())
        {
            switch (_state)
            {
                case IDLE:
                {
                    // Nothing happening so we can abort and complete
                    _state = State.COMPLETE;
                    doAbort = true;
                    doCompleteFailure = true;
                    break;
                }
                case PROCESSING:
                {
                    // Another thread is processing, so we just tell it the state and let it handle it
                    _state = State.PROCESSING_ABORT;
                    break;
                }

                case PROCESSING_CALLED:
                {
                    // Another thread is processing, so we just tell it the state and let it handle it
                    _state = State.PROCESSING_CALLED_ABORT;
                    break;
                }

                case PROCESSING_ABORT:
                case PROCESSING_CALLED_ABORT:
                case PENDING_ABORT:
                {
                    // already aborted, so no change.
                    break;
                }

                case PENDING:
                {
                    // We are waiting for the callback, so we can only call onAbort and then keep waiting
                    _state = State.PENDING_ABORT;
                    doAbort = true;
                    break;
                }

                case COMPLETE:
                {
                    // too late
                    ExceptionUtil.addSuppressedIfNotAssociated(_failure, cause);
                    return false;
                }
            }
            if (_failure == null)
                _failure = cause;
            else
                ExceptionUtil.addSuppressedIfNotAssociated(_failure, cause);
            cause = _failure;
        }

        if (doAbort)
        {
            if (doCompleteFailure)
                ExceptionUtil.call(cause, this::onAbort, this::doCompleteFailure);
            else
                onAbort(cause);
        }
        return true;
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
            // TODO
            return _state == State.COMPLETE && _failure instanceof ClosedChannelException;
        }
    }

    /**
     * @return whether this callback has been {@link #failed(Throwable) failed}
     */
    public boolean isFailed()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _state == State.COMPLETE && _failure != null;
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
            return _state != State.COMPLETE && _failure != null;
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
        return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), _state);
    }
}
