//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.util.thread.AutoLock;

/**
 * This specialized callback implements a pattern that allows
 * a large job to be broken into smaller tasks using iteration
 * rather than recursion.
 * <p>
 * A typical example is the write of a large content to a socket,
 * divided in chunks. Chunk C1 is written by thread T1, which
 * also invokes the callback, which writes chunk C2, which invokes
 * the callback again, which writes chunk C3, and so forth.
 * </p>
 * <p>
 * The problem with the example is that if the callback thread
 * is the same that performs the I/O operation, then the process
 * is recursive and may result in a stack overflow.
 * To avoid the stack overflow, a thread dispatch must be performed,
 * causing context switching and cache misses, affecting performance.
 * </p>
 * <p>
 * To avoid this issue, this callback uses an AtomicReference to
 * record whether success callback has been called during the processing
 * of a sub task, and if so then the processing iterates rather than
 * recurring.
 * </p>
 * <p>
 * Subclasses must implement method {@link #process()} where the sub
 * task is executed and a suitable {@link IteratingCallback.Action} is
 * returned to this callback to indicate the overall progress of the job.
 * This callback is passed to the asynchronous execution of each sub
 * task and a call the {@link #succeeded()} on this callback represents
 * the completion of the sub task.
 * </p>
 */
public abstract class IteratingCallback implements Callback
{
    /**
     * The internal states of this callback
     */
    private enum State
    {
        /**
         * This callback is IDLE, ready to iterate.
         */
        IDLE,

        /**
         * This callback is iterating calls to {@link #process()} and is dealing with
         * the returns.  To get into processing state, it much of held the lock state
         * and set iterating to true.
         */
        PROCESSING,

        /**
         * Waiting for a schedule callback
         */
        PENDING,

        /**
         * Called by a schedule callback
         */
        CALLED,

        /**
         * The overall job has succeeded as indicated by a {@link Action#SUCCEEDED} return
         * from {@link IteratingCallback#process()}
         */
        SUCCEEDED,

        /**
         * The overall job has failed as indicated by a call to {@link IteratingCallback#failed(Throwable)}
         */
        FAILED,

        /**
         * This callback has been closed and cannot be reset.
         */
        CLOSED
    }

    /**
     * The indication of the overall progress of the overall job that
     * implementations of {@link #process()} must return.
     */
    protected enum Action
    {
        /**
         * Indicates that {@link #process()} has no more work to do,
         * but the overall job is not completed yet, probably waiting
         * for additional events to trigger more work.
         */
        IDLE,
        /**
         * Indicates that {@link #process()} is executing asynchronously
         * a sub task, where the execution has started but the callback
         * may have not yet been invoked.
         */
        SCHEDULED,

        /**
         * Indicates that {@link #process()} has completed the overall job.
         */
        SUCCEEDED
    }

    private final AutoLock _lock = new AutoLock();
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
     * Method called by {@link #iterate()} to process the sub task.
     * <p>
     * Implementations must start the asynchronous execution of the sub task
     * (if any) and return an appropriate action:
     * </p>
     * <ul>
     * <li>{@link Action#IDLE} when no sub tasks are available for execution
     * but the overall job is not completed yet</li>
     * <li>{@link Action#SCHEDULED} when the sub task asynchronous execution
     * has been started</li>
     * <li>{@link Action#SUCCEEDED} when the overall job is completed</li>
     * </ul>
     *
     * @return the appropriate Action
     * @throws Throwable if the sub task processing throws
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
     * of sub tasks.  It can be called at any time by any thread, and it's
     * contract is that when called, then the {@link #process()} method will
     * be called during or soon after, either by the calling thread or by
     * another thread.
     */
    public void iterate()
    {
        boolean process = false;

        try (AutoLock lock = _lock.lock())
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

        boolean onCompleteSuccess = false;
        Throwable onCompleteFailure = null;

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
                failed(x);
                break;
            }

            // acted on the action we have just received
            try (AutoLock lock = _lock.lock())
            {
                switch (_state)
                {
                    case PROCESSING:
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
                                    _state = State.PROCESSING;
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
                                onCompleteSuccess = true;
                                break processing;
                            }

                            default:
                                break;
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
                        onCompleteFailure = _failure;
                        _failure = null;
                        break processing;

                    case SUCCEEDED:
                    case CLOSED:
                        break processing;

                    case IDLE:
                    case PENDING:
                    default:
                        throw new IllegalStateException(String.format("%s[action=%s]", this, action));
                }
            }
        }

        if (onCompleteSuccess)
            onCompleteSuccess();
        else if (onCompleteFailure != null)
            onCompleteFailure(onCompleteFailure);
    }

    /**
     * Invoked when the sub task succeeds.
     * Subclasses that override this method must always remember to call
     * {@code super.succeeded()}.
     */
    @Override
    public void succeeded()
    {
        boolean process = false;
        try (AutoLock lock = _lock.lock())
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
     * Invoked when the sub task fails.
     * Subclasses that override this method must always remember to call
     * {@code super.failed(Throwable)}.
     */
    @Override
    public void failed(Throwable x)
    {
        boolean failure = false;
        try (AutoLock lock = _lock.lock())
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

    public void close()
    {
        String failure = null;
        try (AutoLock lock = _lock.lock())
        {
            switch (_state)
            {
                case IDLE:
                case SUCCEEDED:
                case FAILED:
                    _state = State.CLOSED;
                    break;

                case CLOSED:
                    break;

                default:
                    failure = String.format("Close %s in state %s", this, _state);
                    _state = State.CLOSED;
            }
        }

        if (failure != null)
            onCompleteFailure(new IOException(failure));
    }

    /*
     * only for testing
     * @return whether this callback is idle and {@link #iterate()} needs to be called
     */
    boolean isIdle()
    {
        try (AutoLock lock = _lock.lock())
        {
            return _state == State.IDLE;
        }
    }

    public boolean isClosed()
    {
        try (AutoLock lock = _lock.lock())
        {
            return _state == State.CLOSED;
        }
    }

    /**
     * @return whether this callback has failed
     */
    public boolean isFailed()
    {
        try (AutoLock lock = _lock.lock())
        {
            return _state == State.FAILED;
        }
    }

    /**
     * @return whether this callback has succeeded
     */
    public boolean isSucceeded()
    {
        try (AutoLock lock = _lock.lock())
        {
            return _state == State.SUCCEEDED;
        }
    }

    /**
     * Resets this callback.
     * <p>
     * A callback can only be reset to IDLE from the
     * SUCCEEDED or FAILED states or if it is already IDLE.
     * </p>
     *
     * @return true if the reset was successful
     */
    public boolean reset()
    {
        try (AutoLock lock = _lock.lock())
        {
            switch (_state)
            {
                case IDLE:
                    return true;

                case SUCCEEDED:
                case FAILED:
                    _iterate = false;
                    _state = State.IDLE;
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
