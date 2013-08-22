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

package org.eclipse.jetty.util;

import java.util.concurrent.atomic.AtomicReference;


/* ------------------------------------------------------------ */
/** Iterating Callback.
 * <p>This specialized callback is used when breaking up an
 * asynchronous task into smaller asynchronous tasks.  A typical pattern
 * is that a successful callback is used to schedule the next sub task, but 
 * if that task completes quickly and uses the calling thread to callback
 * the success notification, this can result in a growing stack depth.
 * </p>
 * <p>To avoid this issue, this callback uses an AtomicReference to note 
 * if the success callback has been called during the processing of a 
 * sub task, and if so then the processing iterates rather than recurses.
 * </p>
 * <p>This callback is passed to the asynchronous handling of each sub
 * task and a call the {@link #succeeded()} on this call back represents
 * completion of the subtask.  Only once all the subtasks are completed is 
 * the {#completed()} method called.</p>
 *  
 */
public abstract class IteratingCallback implements Callback
{
    private enum State { WAITING, ITERATING, SUCCEEDED, FAILED };
    private final AtomicReference<State> _state = new AtomicReference<>(State.WAITING);
    
    public IteratingCallback()
    {
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Process a subtask.
     * <p>Called by {@link #iterate()} to process a sub task of the overall task
     * <p>
     * @return True if the total task is complete. If false is returned
     * then this Callback must be scheduled to receive either a call to 
     * {@link #succeeded()} or {@link #failed(Throwable)}.
     * @throws Exception
     */
    abstract protected boolean process() throws Exception;
    
    abstract protected void completed();
    
    /* ------------------------------------------------------------ */
    /** This method is called initially to start processing and 
     * is then called by subsequent sub task success to continue
     * processing.
     */
    public void iterate()
    {
        try
        {
            // Keep iterating as long as succeeded() is called during process()
            // If we are in WAITING state, either this is the first iteration or
            // succeeded()/failed() were called already.
            while(_state.compareAndSet(State.WAITING,State.ITERATING))
            {
                // Make some progress by calling process()
                if (process())
                {
                    // A true return indicates we are finished a no further callbacks 
                    // are scheduled. So we must still be ITERATING.
                    if (_state.compareAndSet(State.ITERATING,State.SUCCEEDED))
                        completed();
                    else
                        throw new IllegalStateException("Already "+_state.get());
                    return;
                }
                // else a callback has been scheduled.  If it has not happened yet,
                // we will still be ITERATING
                else if (_state.compareAndSet(State.ITERATING,State.WAITING))
                    // no callback yet, so break the loop and wait for it
                    break;
                
                // The callback must have happened and we are either WAITING already or FAILED
                // the loop test will work out which
            }
        }
        catch(Exception e)
        {
            failed(e);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void succeeded()
    {
        // Try a short cut for the fast method.  If we are still iterating
        if (_state.compareAndSet(State.ITERATING,State.WAITING))
            // then next loop will continue processing, so nothing to do here
            return;

        // OK do it properly
        loop: while(true)
        {
            switch(_state.get())
            {
                case ITERATING:
                    if (_state.compareAndSet(State.ITERATING,State.WAITING))
                        break loop;
                    continue;
                    
                case WAITING:
                    // we are really waiting, so use this callback thread to iterate some more 
                    iterate();
                    break loop;
                    
                default:
                    throw new IllegalStateException("Already "+_state.get());
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * Derivations of this method should always call super.failed(x) 
     * to check the state before handling the failure.
     * @see org.eclipse.jetty.util.Callback#failed(java.lang.Throwable)
     */
    @Override
    public void failed(Throwable x)
    {
        loop: while(true)
        {
            switch(_state.get())
            {
                case ITERATING:
                    if (_state.compareAndSet(State.ITERATING,State.FAILED))
                        break loop;
                    continue;
                    
                case WAITING:
                    if (_state.compareAndSet(State.WAITING,State.FAILED))
                        break loop;
                    continue;
                    
                default:
                    throw new IllegalStateException("Already "+_state.get(),x);
            }
        }
    }
}
