//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util;

import java.io.IOException;

import org.eclipse.jetty.util.thread.Locker;

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
         * or by throwing.
         */
        PROCESSING,

        /**
         * Method {@link #process()} returned {@link Action#SCHEDULED}
         * and this callback is waiting for the asynchronous sub-task
         * to complete.
         */
        PENDING,

        /**
         * The asynchronous sub-task was completed successfully
         * via a call to {@link #succeeded()} while in
         * {@link #PROCESSING} state.
         */
        CALLED,

        /**
         * The iteration terminated successfully as indicated by
         * {@link Action#SUCCEEDED} returned from
         * {@link IteratingCallback#process()}.
         */
        SUCCEEDED,

        /**
         * The iteration terminated with a failure via a call
         * to {@link IteratingCallback#failed(Throwable)}.
         */
        FAILED,

        /**
         * This callback has been {@link #close() closed} and
         * cannot be {@link #reset() reset}.
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

    private Locker _locker = new Locker();
    private State _state;
    private Throwable _failure;
    private boolean _iterate;

    protected IteratingCallback()
    {
        _state = State.IDLE;
    }

    protected IteratingCallback(boolean needReset)
    {
        _state = needReset ? State.SUCCEEDED : State.IDLE;
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

        try (Locker.Lock lock = _locker.lock())
        {
            switch (_state)
            {
                case PENDING:
                case CALLED:
                    // process will be called when callback is handled
                    break;

                case IDLE:
                    _state = State.PROCESSING;
                    process = true;
                    break;

                case PROCESSING:
                    _iterate = true;
                    break;

                case FAILED:
                case SUCCEEDED:
                    break;

                case CLOSED:
                default:
                    throw new IllegalStateException(toString());
            }
        }
        if (process)
            processing();
    }

    private void processing()
    {
        // This should only ever be called when in processing state, however a failed or close call
        // may happen concurrently, so state is not assumed.

        boolean notifyCompleteSuccess = false;
        Throwable notifyCompleteFailure = null;

        // While we are processing
        processing:
        while (true)
        {
            // Call process to get the action that we have to take.
            Action action = null;
            try
            {
                action = process();
            }
            catch (Throwable x)
            {
                failed(x);
                // Fall through to possibly invoke onCompleteFailure().
            }

            // acted on the action we have just received
            try (Locker.Lock lock = _locker.lock())
            {
                switch (_state)
                {
                    case PROCESSING:
                    {
                        if (action != null)
                        {
                            switch (action)
                            {
                                case IDLE:
                                {
                                    // Has iterate been called while we were processing?
                                    if (_iterate)
                                    {
                                        // yes, so skip idle and keep processing
                                        _iterate = false;
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
                                    _iterate = false;
                                    _state = State.SUCCEEDED;
                                    notifyCompleteSuccess = true;
                                    break processing;
                                }
                                default:
                                {
                                    break;
                                }
                            }
                        }
                        throw new IllegalStateException(String.format("%s[action=%s]", this, action));
                    }

                    case CALLED:
                    {
                        if (action != Action.SCHEDULED)
                            throw new IllegalStateException(String.format("%s[action=%s]", this, action));
                        // we lost the race, so we have to keep processing
                        _state = State.PROCESSING;
                        continue;
                    }

                    case FAILED:
                    case CLOSED:
                        notifyCompleteFailure = _failure;
                        _failure = null;
                        break processing;

                    case SUCCEEDED:
                        break processing;

                    case IDLE:
                    case PENDING:
                    default:
                        throw new IllegalStateException(String.format("%s[action=%s]", this, action));
                }
            }
        }

        if (notifyCompleteSuccess)
            onCompleteSuccess();
        else if (notifyCompleteFailure != null)
            onCompleteFailure(notifyCompleteFailure);
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
        try (Locker.Lock lock = _locker.lock())
        {
            switch (_state)
            {
                case PROCESSING:
                {
                    _state = State.CALLED;
                    break;
                }
                case PENDING:
                {
                    _state = State.PROCESSING;
                    process = true;
                    break;
                }
                case CLOSED:
                case FAILED:
                {
                    // Too late!
                    break;
                }
                default:
                {
                    throw new IllegalStateException(toString());
                }
            }
        }
        if (process)
            processing();
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
    public void failed(Throwable x)
    {
        boolean failure = false;
        try (Locker.Lock lock = _locker.lock())
        {
            switch (_state)
            {
                case SUCCEEDED:
                case FAILED:
                case IDLE:
                case CLOSED:
                case CALLED:
                    // too late!.
                    break;
                case PENDING:
                {
                    failure = true;
                    break;
                }
                case PROCESSING:
                {
                    _state = State.FAILED;
                    _failure = x;
                    break;
                }
                default:
                    throw new IllegalStateException(toString());
            }
        }
        if (failure)
            onCompleteFailure(x);
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
        String failure = null;
        try (Locker.Lock lock = _locker.lock())
        {
            switch (_state)
            {
                case IDLE:
                case SUCCEEDED:
                case FAILED:
                    _state = State.CLOSED;
                    break;

                case PROCESSING:
                    _failure = new IOException(String.format("Close %s in state %s", this, _state));
                    _state = State.CLOSED;
                    break;

                case CLOSED:
                    break;

                default:
                    failure = String.format("Close %s in state %s", this, _state);
                    _state = State.CLOSED;
                    break;
            }
        }

        if (failure != null)
            onCompleteFailure(new IOException(failure));
    }

    /**
     * @return whether this callback is idle, and {@link #iterate()} needs to be called
     */
    boolean isIdle()
    {
        try (Locker.Lock lock = _locker.lock())
        {
            return _state == State.IDLE;
        }
    }

    /**
     * @return whether this callback has been {@link #close() closed}
     */
    public boolean isClosed()
    {
        try (Locker.Lock lock = _locker.lock())
        {
            return _state == State.CLOSED;
        }
    }

    /**
     * @return whether this callback has been {@link #failed(Throwable) failed}
     */
    public boolean isFailed()
    {
        try (Locker.Lock lock = _locker.lock())
        {
            return _state == State.FAILED;
        }
    }

    /**
     * @return whether this callback and the overall asynchronous task has been succeeded
     *
     * @see #onCompleteSuccess()
     */
    public boolean isSucceeded()
    {
        try (Locker.Lock lock = _locker.lock())
        {
            return _state == State.SUCCEEDED;
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
        try (Locker.Lock lock = _locker.lock())
        {
            switch (_state)
            {
                case IDLE:
                    return true;

                case SUCCEEDED:
                case FAILED:
                    _state = State.IDLE;
                    _failure = null;
                    _iterate = false;
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
