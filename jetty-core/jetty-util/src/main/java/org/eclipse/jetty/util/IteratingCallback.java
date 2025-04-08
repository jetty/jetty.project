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
import java.util.function.Consumer;

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
    private final Runnable _onSuccess = this::onSuccess;
    private final Runnable _processing = this::processing;
    private final Consumer<Throwable> _onCompleted = this::onCompleted;
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
     * Invoked when one task has completed successfully, either by the
     * caller thread or by the processing thread. This invocation is
     * always serialized w.r.t the execution of {@link #process()}.
     * <p>
     * This method is not invoked when a call to {@link #abort(Throwable)}
     * is made before the {@link #succeeded()} callback happens.
     */
    protected void onSuccess()
    {
    }

    /**
     * Invoked when the overall task has been {@link #abort(Throwable) aborted} or {@link #failed(Throwable) failed}.
     * <p>
     * Calls to this method are serialized with respect to {@link #onAborted(Throwable)}, {@link #process()},
     * {@link #onCompleteFailure(Throwable)} and {@link #onCompleted(Throwable)}.
     * <p>
     * Because {@code onFailure} can be called due to an {@link #abort(Throwable)} or {@link #close()} operation, it is
     * possible that any resources passed to a {@link Action#SCHEDULED} operation may still be in use, and thus should not
     * be recycled by this call. For example any buffers passed to a write operation should not be returned to a buffer
     * pool by implementations of {@code onFailure}.   Such resources may be discarded here, or safely recycled in a
     * subsequent call to {@link #onCompleted(Throwable)} or {@link #onCompleteFailure(Throwable)}, when
     * the {@link Action#SCHEDULED} operation has completed.
     * @param cause The cause of the failure or abort
     * @see #onCompleted(Throwable)
     * @see #onCompleteFailure(Throwable)
     */
    protected void onFailure(Throwable cause)
    {
    }

    /**
     * Invoked when the overall task has completed successfully, specifically after any {@link Action#SCHEDULED} operations
     * have {@link Callback#succeeded()} and {@link #process()} has returned {@link Action#SUCCEEDED}.
     * <p>
     * Calls to this method are serialized with respect to {@link #process()}, {@link #onAborted(Throwable)}
     * and {@link #onCompleted(Throwable)}.
     * If this method is called, then {@link #onCompleteFailure(Throwable)} ()} will never be called.
     *
     * @see #onCompleteFailure(Throwable)
     */
    protected void onCompleteSuccess()
    {
    }

    /**
     * Invoked when the overall task has completed with a failure.
     * <p>
     * Calls to this method are serialized with respect to {@link #process()}, {@link #onAborted(Throwable)}
     * and {@link #onCompleted(Throwable)}.
     * If this method is called, then {@link #onCompleteSuccess()} will never be called.
     *
     * @param cause the throwable to indicate cause of failure
     * @see #onCompleteSuccess()
     */
    protected void onCompleteFailure(Throwable cause)
    {
    }

    /**
     * Invoked when the overall task has been aborted.
     * <p>
     * Calls to this method are serialized with respect to {@link #process()}, {@link #onCompleteFailure(Throwable)}
     * and {@link #onCompleted(Throwable)}.
     * If this method is called, then {@link #onCompleteSuccess()} will never be called.
     * <p>
     * The default implementation of this method calls {@link #failed(Throwable)}.  Overridden implementations of
     * this method SHOULD NOT call {@code super.onAborted(Throwable)}.
     * <p>
     * Because {@code onAborted} can be called due to an {@link #abort(Throwable)} or {@link #close()} operation, it is
     * possible that any resources passed to a {@link Action#SCHEDULED} operation may still be in use, and thus should not
     * be recycled by this call. For example any buffers passed to a write operation should not be returned to a buffer
     * pool by implementations of {@code onFailure}.   Such resources may be discarded here, or safely recycled in a
     * subsequent call to {@link #onCompleted(Throwable)} or {@link #onCompleteFailure(Throwable)}, when
     * the {@link Action#SCHEDULED} operation has completed.
     * @param cause The cause of the abort
     * @see #onCompleted(Throwable)
     * @see #onCompleteFailure(Throwable)
     */
    protected void onAborted(Throwable cause)
    {
    }

    /**
     * Invoked when the overall task has completed.
     * <p>
     * Calls to this method are serialized with respect to {@link #process()} and {@link #onAborted(Throwable)}.
     * The default implementation of this method will call either {@link #onCompleteSuccess()} or {@link #onCompleteFailure(Throwable)}
     * thus implementations of this method should always call {@code super.onCompleted(Throwable)}.
     *
     * @param causeOrNull the cause of any {@link #abort(Throwable) abort} or {@link #failed(Throwable) failure},
     * else {@code null} for {@link #succeeded() success}.
     */
    protected void onCompleted(Throwable causeOrNull)
    {
        if (causeOrNull == null)
            onCompleteSuccess();
        else
            onCompleteFailure(causeOrNull);
    }

    private void doOnSuccessProcessing()
    {
        ExceptionUtil.callAndThen(_onSuccess, _processing);
    }

    private void doCompleteSuccess()
    {
        onCompleted(null);
    }

    private void doOnCompleted(Throwable cause)
    {
        ExceptionUtil.call(cause, _onCompleted);
    }

    private void doOnFailureOnCompleted(Throwable cause)
    {
        ExceptionUtil.callAndThen(cause, this::onFailure, _onCompleted);
    }

    private void doOnAbortedOnFailure(Throwable cause)
    {
        ExceptionUtil.callAndThen(cause, this::onAborted, this::onFailure);
    }

    private void doOnAbortedOnFailureOnCompleted(Throwable cause)
    {
        ExceptionUtil.callAndThen(cause, this::doOnAbortedOnFailure, _onCompleted);
    }

    private void doOnAbortedOnFailureIfNotPendingDoCompleted(Throwable cause)
    {
        ExceptionUtil.callAndThen(cause, this::doOnAbortedOnFailure, this::ifNotPendingDoCompleted);
    }

    private void ifNotPendingDoCompleted()
    {
        Throwable completeFailure = null;
        try (AutoLock ignored = _lock.lock())
        {
            _failure = _failure.getCause();

            if (Objects.requireNonNull(_state) != State.PENDING)
            {
                // the callback completed, one way or another, so it is up to us to do the completion
                completeFailure = _failure;
            }
        }

        if (completeFailure != null)
            doOnCompleted(completeFailure);
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

        boolean completeSuccess = false;
        Throwable onAbortedOnFailureOnCompleted = null;
        Throwable onFailureOnCompleted = null;
        Throwable onAbortedOnFailureIfNotPendingDoCompleted = null;

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

            boolean callOnSuccess = false;
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
                                    onAbortedOnFailureOnCompleted = _failure;
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
                                _reprocess = false;
                                if (_aborted)
                                {
                                    onAbortedOnFailureIfNotPendingDoCompleted = _failure;
                                    _failure = new AbortingException(onAbortedOnFailureIfNotPendingDoCompleted);
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
                                    onAbortedOnFailureOnCompleted = _failure;
                                }
                                else
                                {
                                    _state = State.COMPLETE;
                                    completeSuccess = true;
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
                        {
                            _state = State.CLOSED;
                            onAbortedOnFailureOnCompleted = new IllegalStateException("Action not scheduled");
                            if (_failure == null)
                            {
                                _failure = onAbortedOnFailureOnCompleted;
                            }
                            else
                            {
                                ExceptionUtil.addSuppressedIfNotAssociated(_failure, onAbortedOnFailureIfNotPendingDoCompleted);
                                onAbortedOnFailureOnCompleted = _failure;
                            }
                            break processing;
                        }
                        if (_failure != null)
                        {
                            if (_aborted)
                                onAbortedOnFailureOnCompleted = _failure;
                            else
                                onFailureOnCompleted = _failure;
                            _state = _failure instanceof ClosedException ? State.CLOSED : State.COMPLETE;
                            break processing;
                        }
                        callOnSuccess = true;
                        _state = State.PROCESSING;
                        _reprocess = false;
                        break;
                    }

                    default:
                        throw new IllegalStateException(String.format("%s[action=%s]", this, action));
                }
            }
            finally
            {
                if (callOnSuccess)
                    onSuccess();
            }
        }
        if (onAbortedOnFailureOnCompleted != null)
            doOnAbortedOnFailureOnCompleted(onAbortedOnFailureOnCompleted);
        else if (completeSuccess)
            doCompleteSuccess();
        else if (onFailureOnCompleted != null)
            doOnFailureOnCompleted(onFailureOnCompleted);
        else if (onAbortedOnFailureIfNotPendingDoCompleted != null)
            doOnAbortedOnFailureIfNotPendingDoCompleted(onAbortedOnFailureIfNotPendingDoCompleted);
    }

    /**
     * Method to invoke when the asynchronous sub-task succeeds.
     * <p>
     * For most purposes, this method should be considered {@code final} and should only be
     * overridden in extraordinary circumstances.
     * Subclasses that override this method must always call {@code super.succeeded()}.
     * Such overridden methods are not serialized with respect to {@link #process()}, {@link #onCompleteSuccess()},
     * {@link #onCompleteFailure(Throwable)}, nor {@link #onAborted(Throwable)}. They should not act on nor change any
     * fields that may be used by those methods.
     * Eventually, {@link #onSuccess()} is
     * called, either by the caller thread or by the processing
     * thread.
     */
    @Override
    public final void succeeded()
    {
        boolean onSuccessProcessing = false;
        Throwable onCompleted = null;
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
                            onCompleted = _failure;
                        }
                    }
                    else
                    {
                        // No other thread is processing, so we will do the processing
                        _state = State.PROCESSING;
                        onSuccessProcessing = true;
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
        if (onSuccessProcessing)
        {
            doOnSuccessProcessing();
        }
        else if (onCompleted != null)
        {
            doOnCompleted(onCompleted);
        }
    }

    /**
     * Method to invoke when the asynchronous sub-task fails,
     * or to fail the overall asynchronous task and therefore
     * terminate the iteration.
     * <p>
     * Eventually, {@link #onCompleteFailure(Throwable)} is
     * called, either by the caller thread or by the processing
     * thread.
     * <p>
     * For most purposes, this method should be considered {@code final} and should only be
     * overridden in extraordinary circumstances.
     * Subclasses that override this method must always call {@code super.succeeded()}.
     * Such overridden methods are not serialized with respect to {@link #process()}, {@link #onCompleteSuccess()},
     * {@link #onCompleteFailure(Throwable)}, nor {@link #onAborted(Throwable)}. They should not act on nor change any
     * fields that may be used by those methods.
     * @see #isFailed()
     */
    @Override
    public final void failed(Throwable cause)
    {
        cause = Objects.requireNonNullElseGet(cause, IOException::new);

        Throwable onFailureOnCompleted = null;
        Throwable onCompleted = null;
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
                            onCompleted = _failure;
                        }
                    }
                    else
                    {
                        // No other thread is processing, so we will do the processing
                        _state = State.COMPLETE;
                        _failure = cause;
                        onFailureOnCompleted = _failure;
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
        if (onFailureOnCompleted != null)
            doOnFailureOnCompleted(onFailureOnCompleted);
        else if (onCompleted != null)
            doOnCompleted(onCompleted);
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
    public final void close()
    {
        Throwable onAbortedOnFailureIfNotPendingDoCompleted = null;
        Throwable onAbortedOnFailureOnCompleted = null;

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
                    onAbortedOnFailureOnCompleted = _failure;
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
                    onAbortedOnFailureIfNotPendingDoCompleted = new ClosedException();
                    _failure = new AbortingException(onAbortedOnFailureIfNotPendingDoCompleted);
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

        if (onAbortedOnFailureIfNotPendingDoCompleted != null)
            doOnAbortedOnFailureIfNotPendingDoCompleted(onAbortedOnFailureIfNotPendingDoCompleted);
        else if (onAbortedOnFailureOnCompleted != null)
            doOnAbortedOnFailureOnCompleted(onAbortedOnFailureOnCompleted);
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
    public final boolean abort(Throwable cause)
    {
        cause = Objects.requireNonNullElseGet(cause, Throwable::new);

        boolean onAbort = false;
        boolean onAbortedOnFailureOnCompleted = false;
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
                    onAbortedOnFailureOnCompleted = true;
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

        if (onAbortedOnFailureOnCompleted)
            doOnAbortedOnFailureOnCompleted(cause);
        else if (onAbort)
            doOnAbortedOnFailureIfNotPendingDoCompleted(cause);

        return true;
    }

    boolean isPending()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _state == State.PENDING;
        }
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
        try (AutoLock ignored = _lock.tryLock())
        {
            String held = _lock.isHeldByCurrentThread() ? "" : "?";
            return String.format("%s@%x[%s:%s,aborted=%b,failure=%s]", getClass().getSimpleName(), hashCode(), held, _state, _aborted, _failure);
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
