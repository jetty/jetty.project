//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.nio.channels.ClosedChannelException;
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
        CLOSED,
        
        /**
         * State is locked while leaving processing state to check the iterate boolean
         */
        LOCKED
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
    private boolean _iterate;
    
    
    protected IteratingCallback()
    {
        _state = new AtomicReference<>(State.IDLE);
    }
    
    protected IteratingCallback(boolean needReset)
    {
        _state = new AtomicReference<>(needReset ? State.SUCCEEDED : State.IDLE);
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
     * @deprecated Use {@link #onCompleteSuccess()} instead.
     */
    @Deprecated
    protected void completed()
    {
    }

    /**
     * Invoked when the overall task has completed successfully.
     *
     * @see #onCompleteFailure(Throwable)
     */
    protected void onCompleteSuccess()
    {
        completed();
    }
    
    /**
     * Invoked when the overall task has completed with a failure.
     *
     * @see #onCompleteSuccess()
     */
    protected void onCompleteFailure(Throwable x)
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
        loop: while (true)
        {
            State state=_state.get();
            switch (state)
            {
                case PENDING:
                case CALLED:
                    // process will be called when callback is handled
                    break loop;
                    
                case IDLE:
                    if (!_state.compareAndSet(state,State.PROCESSING))
                        continue;
                    processing();
                    break loop;
                    
                case PROCESSING:
                    if (!_state.compareAndSet(state,State.LOCKED))
                        continue;
                    // Tell the thread that is processing that it must iterate again
                    _iterate=true;
                    _state.set(State.PROCESSING);
                    break loop;
                    
                case LOCKED:
                    Thread.yield();
                    continue loop;

                case FAILED:
                case SUCCEEDED:
                    break loop;

                case CLOSED:
                default:
                    throw new IllegalStateException("state="+state);
            }
        }
    }

    private void processing() 
    {
        // This should only ever be called when in processing state, however a failed or close call
        // may happen concurrently, so state is not assumed.
        
        // While we are processing
        processing: while (true)
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
                break processing;
            }

            // loop until we have successfully acted on the action we have just received
            acting: while(true)
            {
                // action handling needs to know the state
                State state=_state.get();
                
                switch (state)
                {
                    case PROCESSING:
                    {
                        switch (action)
                        {
                            case IDLE:
                            {
                                // lock the state
                                if (!_state.compareAndSet(state,State.LOCKED))
                                    continue acting;

                                // Has iterate been called while we were processing?
                                if (_iterate)
                                {
                                    // yes, so skip idle and keep processing
                                    _iterate=false;
                                    _state.set(State.PROCESSING);
                                    continue processing;
                                }

                                // No, so we can go idle
                                _state.set(State.IDLE);
                                break processing;
                            }
                            
                            case SCHEDULED:
                            {
                                if (!_state.compareAndSet(state, State.PENDING))
                                    continue acting;
                                // we won the race against the callback, so the callback has to process and we can break processing
                                break processing;
                            }
                            
                            case SUCCEEDED:
                            {
                                if (!_state.compareAndSet(state, State.LOCKED))
                                    continue acting;
                                _iterate=false;
                                _state.set(State.SUCCEEDED);
                                onCompleteSuccess();
                                break processing;
                            }

                            default:
                                throw new IllegalStateException("state="+state+" action="+action); 
                        }
                    }
                    
                    case CALLED:
                    {
                        switch (action)
                        {
                            case SCHEDULED:
                            {
                                if (!_state.compareAndSet(state, State.PROCESSING))
                                    continue acting;
                                // we lost the race, so we have to keep processing
                                continue processing;
                            }

                            default:
                                throw new IllegalStateException("state="+state+" action="+action); 
                        }
                    }
                        
                    case LOCKED:
                        Thread.yield();
                        continue acting;

                    case SUCCEEDED:
                    case FAILED:
                    case CLOSED:
                        break processing;

                    case IDLE:
                    case PENDING:
                    default:
                        throw new IllegalStateException("state="+state+" action="+action); 
                }
            }
        }
    }
    
    /**
     * Invoked when the sub task succeeds.
     * Subclasses that override this method must always remember to call
     * {@code super.succeeded()}.
     */
    @Override
    public void succeeded()
    {
        loop: while (true)
        {
            State state = _state.get();
            switch (state)
            {
                case PROCESSING:
                {
                    if (!_state.compareAndSet(state, State.CALLED))
                        continue loop;
                    break loop;
                }
                case PENDING:
                {
                    if (!_state.compareAndSet(state, State.PROCESSING))
                        continue loop;
                    processing();
                    break loop;
                }
                case CLOSED:
                case FAILED:
                {
                    // Too late!
                    break loop;
                }
                case LOCKED:
                {
                    Thread.yield();
                    continue loop;
                }       
                default:
                {
                    throw new IllegalStateException("state="+state);
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
    public void failed(Throwable x)
    {
        loop: while (true)
        {
            State state = _state.get();
            switch (state)
            {
                case SUCCEEDED:
                case FAILED:
                case IDLE:
                case CLOSED:
                case CALLED:
                {
                    // too late!.
                    break loop;
                }
                case LOCKED:
                {
                    Thread.yield();
                    continue loop;
                }  
                case PENDING: 
                case PROCESSING: 
                {
                    if (!_state.compareAndSet(state, State.FAILED))
                        continue loop;

                    onCompleteFailure(x);
                    break loop;
                }
                default:
                    throw new IllegalStateException("state="+state);
            }
        }
    }

    public void close()
    {
        loop: while (true)
        {
            State state = _state.get();
            switch (state)
            {
                case IDLE:
                case SUCCEEDED:
                case FAILED:
                {
                    if (!_state.compareAndSet(state, State.CLOSED))
                        continue loop;
                    break loop;
                }
                case CLOSED:
                {
                    break loop;
                }
                case LOCKED:
                {
                    Thread.yield();
                    continue loop;
                }    
                default:
                {
                    if (!_state.compareAndSet(state, State.CLOSED))
                        continue loop;
                    onCompleteFailure(new ClosedChannelException());
                    break loop;
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
        return _state.get() == State.IDLE;
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

    /**
     * Resets this callback.
     * <p/>
     * A callback can only be reset to IDLE from the
     * SUCCEEDED or FAILED states or if it is already IDLE.
     *
     * @return true if the reset was successful
     */
    public boolean reset()
    {
        while (true)
        {
            State state=_state.get();
            switch(state)
            {
                case IDLE:
                    return true;
                    
                case SUCCEEDED:
                    if (!_state.compareAndSet(state, State.LOCKED))
                        continue;
                    _iterate=false;
                    _state.set(State.IDLE);
                    return true;
                    
                case FAILED:
                    if (!_state.compareAndSet(state, State.LOCKED))
                        continue;
                    _iterate=false;
                    _state.set(State.IDLE);
                    return true;

                case LOCKED:
                    Thread.yield();
                    continue;
                    
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
