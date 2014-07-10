//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.atomic.AtomicReference;

/**
 * This specialized callback implements a pattern that allows
 * a large job to be broken into smaller tasks using iteration
 * rather than recursion.
 * <p/>
 * A typical example is the write of a large content to a socket,
 * divided in chunks. Chunk C1 is written by thread T1, which
 * also invokes the callback, which writes chunk C2, which invokes
 * the callback again, which writes chunk C3, and so forth.
 * <p/>
 * The problem with the example is that if the callback thread
 * is the same that performs the I/O operation, then the process
 * is recursive and may result in a stack overflow.
 * To avoid the stack overflow, a thread dispatch must be performed,
 * causing context switching and cache misses, affecting performance.
 * <p/>
 * To avoid this issue, this callback uses an AtomicReference to
 * record whether success callback has been called during the processing
 * of a sub task, and if so then the processing iterates rather than
 * recurring.
 * <p/>
 * Subclasses must implement method {@link #process()} where the sub
 * task is executed and a suitable {@link IteratingCallback.Action} is
 * returned to this callback to indicate the overall progress of the job.
 * This callback is passed to the asynchronous execution of each sub
 * task and a call the {@link #succeeded()} on this callback represents
 * the completion of the sub task.
 */
public abstract class IteratingCallback implements Callback
{

    /**
     * The internal states of this callback
     */
    private enum State
    {
        /**
         * This callback is inactive, ready to iterate.
         */
        INACTIVE,
        /**
         * This callback is iterating and {@link #process()} has scheduled an
         * asynchronous operation by returning {@link Action#SCHEDULED}, but
         * the operation is still undergoing.
         */
        ACTIVE,
        /**
         * This callback is iterating and {@link #process()} has been called
         * but not returned yet.
         */
        ITERATING,
        /**
         * While this callback was iterating, another request for iteration
         * has been issued, so the iteration must continue even if a previous
         * call to {@link #process()} returned {@link Action#IDLE}.
         */
        ITERATE_AGAIN,
        /**
         * The overall job has succeeded.
         */
        SUCCEEDED,
        /**
         * The overall job has failed.
         */
        FAILED,
        /**
         * The ICB has been closed and cannot be reset
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

    private final AtomicReference<State> _state;
    
    protected IteratingCallback()
    {
        _state = new AtomicReference<>(State.INACTIVE);
    }
    
    protected IteratingCallback(boolean needReset)
    {
        _state = new AtomicReference<>(needReset?State.SUCCEEDED:State.INACTIVE);
    }
    
    protected State getState()
    {
        return _state.get();
    }

    /**
     * Method called by {@link #iterate()} to process the sub task.
     * <p/>
     * Implementations must start the asynchronous execution of the sub task
     * (if any) and return an appropriate action:
     * <ul>
     * <li>{@link Action#IDLE} when no sub tasks are available for execution
     * but the overall job is not completed yet</li>
     * <li>{@link Action#SCHEDULED} when the sub task asynchronous execution
     * has been started</li>
     * <li>{@link Action#SUCCEEDED} when the overall job is completed</li>
     * </ul>
     *
     * @throws Exception if the sub task processing throws
     */
    protected abstract Action process() throws Exception;

    /**
     * Invoked when the overall task has completed successfully.
     */
    protected abstract void onCompleteSuccess();
    
    /**
     * Invoked when the overall task has completely failed.
     */
    protected abstract void onCompleteFailure(Throwable x);

    /**
     * This method must be invoked by applications to start the processing
     * of sub tasks.
     * <p/>
     * If {@link #process()} returns {@link Action#IDLE}, then this method
     * should be called again to restart processing.
     * It is safe to call iterate multiple times from multiple threads since only
     * the first thread to move the state out of INACTIVE will actually do any iteration
     * and processing.
     */
    public void iterate()
    {
        try
        {
            while (true)
            {
                switch (_state.get())
                {
                    case INACTIVE:
                    {
                        if (processIterations())
                            return;
                        break;
                    }
                    case ITERATING:
                    {
                        if (_state.compareAndSet(State.ITERATING, State.ITERATE_AGAIN))
                            return;
                        break;
                    }
                    default:
                    {
                        return;
                    }
                }
            }
        }
        catch (Throwable x)
        {
            failed(x);
        }
    }

    private boolean processIterations() throws Exception
    {
        // Keeps iterating as long as succeeded() is called during process().
        // If we are in INACTIVE state, either this is the first iteration or
        // succeeded()/failed() were called already.
        while (_state.compareAndSet(State.INACTIVE, State.ITERATING))
        {
            // Method process() can only be called by one thread at a time because
            // it is guarded by the CaS above. However, the case blocks below may
            // be executed concurrently in this case: T1 calls process() which
            // executes the asynchronous sub task, which calls succeeded(), which
            // moves the state into INACTIVE, then returns SCHEDULED; T2 calls
            // iterate(), state is now INACTIVE and process() is called again and
            // returns another action. Now we have 2 threads that may execute the
            // action case blocks below concurrently; therefore each case block
            // has to be prepared to fail the CaS it's doing.

            Action action = process();
            switch (action)
            {
                case IDLE:
                {
                    // No more progress can be made.
                    if (_state.compareAndSet(State.ITERATING, State.INACTIVE))
                        return true;

                    // Was iterate() called again since we already decided to go INACTIVE ?
                    // If so, try another iteration as more work may have been added
                    // while the previous call to process() was returning.
                    if (_state.compareAndSet(State.ITERATE_AGAIN, State.INACTIVE))
                        continue;

                    // State may have changed concurrently, try again.
                    continue;
                }
                case SCHEDULED:
                {
                    // The sub task is executing, and the callback for it may or
                    // may not have already been called yet, which we figure out below.
                    // Can double CaS here because state never changes directly ITERATING_AGAIN --> ITERATE.
                    if (_state.compareAndSet(State.ITERATING, State.ACTIVE) ||
                            _state.compareAndSet(State.ITERATE_AGAIN, State.ACTIVE))
                        // Not called back yet, so wait.
                        return true;
                    // Call back must have happened, so iterate.
                    continue;
                }
                case SUCCEEDED:
                {
                    // The overall job has completed.
                    while (true)
                    {
                        State current = _state.get();
                        switch(current)
                        {
                            case SUCCEEDED:
                            case FAILED:
                                // Already complete!.
                                return true;
                            case CLOSED:
                                throw new IllegalStateException();
                            default:
                                if (_state.compareAndSet(current, State.SUCCEEDED))
                                {
                                    onCompleteSuccess();
                                    return true;
                                }
                        }
                    }
                }
                default:
                {
                    throw new IllegalStateException(toString());
                }
            }
        }
        return false;
    }

    /**
     * Invoked when the sub task succeeds.
     * Subclasses that override this method must always remember to call
     * {@code super.succeeded()}.
     */
    @Override
    public void succeeded()
    {
        while (true)
        {
            State current = _state.get();
            switch (current)
            {
                case ITERATE_AGAIN:
                case ITERATING:
                {
                    if (_state.compareAndSet(current, State.INACTIVE))
                        return;
                    continue;
                }
                case ACTIVE:
                {
                    // If we can move from ACTIVE to INACTIVE
                    // then we are responsible to call iterate().
                    if (_state.compareAndSet(current, State.INACTIVE))
                        iterate();
                    // If we can't CaS, then failed() must have been
                    // called, and we just return.
                    return;
                }
                case INACTIVE:
                {
                    // Support the case where the callback is scheduled
                    // externally without a call to iterate().
                    iterate();
                    return;
                }
                case CLOSED:
                    // too late!
                    return;
                    
                default:
                {
                    throw new IllegalStateException(toString());
                }
            }
        }
    }

    /**
     * Invoked when the sub task fails.
     * Subclasses that override this method must always remember to call
     * {@code super.failed(Throwable)}.
     */
    @Override
    public final void failed(Throwable x)
    {
        while (true)
        {
            State current = _state.get();
            switch(current)
            {
                case SUCCEEDED:
                case FAILED:
                case INACTIVE:
                case CLOSED:
                    // Already complete!.
                    return;
                    
                default:
                    if (_state.compareAndSet(current, State.FAILED))
                    {
                        onCompleteFailure(x);
                        return;
                    }
            }
        }
    }

    public final void close()
    {
        while (true)
        {
            State current = _state.get();
            switch(current)
            {
                case INACTIVE:
                case SUCCEEDED:
                case FAILED:
                    if (_state.compareAndSet(current, State.CLOSED))
                        return;
                    break;
                default:
                    if (_state.compareAndSet(current, State.CLOSED))
                    {
                        onCompleteFailure(new IllegalStateException("Closed with pending callback "+this));
                        return;
                    }
            }
        }
    }

    /*
     * only for testing
     * @return whether this callback is idle and {@link #iterate()} needs to be called
     */
    boolean isIdle()
    {
        return _state.get() == State.INACTIVE;
    }

    public boolean isClosed()
    {
        return _state.get() == State.CLOSED;
    }
    
    /**
     * @return whether this callback has failed
     */
    public boolean isFailed()
    {
        return _state.get() == State.FAILED;
    }

    /**
     * @return whether this callback has succeeded
     */
    public boolean isSucceeded()
    {
        return _state.get() == State.SUCCEEDED;
    }

    /* ------------------------------------------------------------ */
    /** Reset the callback
     * <p>A callback can only be reset to INACTIVE from the SUCCEEDED or FAILED states or if it is already INACTIVE.
     * @return True if the reset was successful
     */
    public boolean reset()
    {
        while (true)
        {
            switch(_state.get())
            {
                case INACTIVE:
                    return true;
                    
                case SUCCEEDED:
                    if (_state.compareAndSet(State.SUCCEEDED, State.INACTIVE))
                        return true;
                    break;
                    
                case FAILED:
                    if (_state.compareAndSet(State.FAILED, State.INACTIVE))
                        return true;
                    break;
                    
                default:
                    return false;
            }
        }
    }
    
    @Override
    public String toString()
    {
        return String.format("%s[%s]", super.toString(), _state);
    }
}
