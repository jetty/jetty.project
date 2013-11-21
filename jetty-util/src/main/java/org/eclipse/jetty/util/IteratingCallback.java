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
    protected enum State { IDLE, SCHEDULED, ITERATING, SUCCEEDED, FAILED };
    private final AtomicReference<State> _state = new AtomicReference<>(State.IDLE);
    
    public IteratingCallback()
    {
    }
    
    abstract protected void completed();
    
    /**
     * Method called by iterate to process the task. 
     * @return Then next state:
     * <dl>
     * <dt>SUCCEEDED</dt><dd>if process returns true</dd>
     * <dt>SCHEDULED</dt><dd>This callback has been scheduled and {@link #succeeded()} or {@link #failed(Throwable)} will evenutally be called (if they have not been called already!)</dd>
     * <dt>IDLE</dt><dd>no progress can be made and another call to {@link #iterate()} is required in order to progress the task</dd>
     * <dt>FAILED</dt><dd>processing has failed</dd>
     * </dl>
     * 
     * @throws Exception
     */
    abstract protected State process() throws Exception;
     
    
    /* ------------------------------------------------------------ */
    /** This method is called initially to start processing and 
     * is then called by subsequent sub task success to continue
     * processing.  If {@link #process()} returns IDLE, then iterate should be called 
     * again to restart processing.
     * It is safe to call iterate multiple times as only the first thread to move 
     * the state out of IDLE will actually do any iteration and processing.
     */
    public void iterate()
    {
        try
        {
            // Keep iterating as long as succeeded() is called during process()
            // If we are in WAITING state, either this is the first iteration or
            // succeeded()/failed() were called already.
            while(_state.compareAndSet(State.IDLE,State.ITERATING))
            {
                State next = process();
                switch (next)
                {
                    case SUCCEEDED:
                        // The task has complete, there should have been no callbacks
                        if (!_state.compareAndSet(State.ITERATING,State.SUCCEEDED))
                            throw new IllegalStateException("state="+_state.get());
                        completed();
                        return;
                        
                    case SCHEDULED:
                        // This callback has been scheduled, so it may or may not have 
                        // already been called back.  Let's find out
                        if (_state.compareAndSet(State.ITERATING,State.SCHEDULED))
                            // not called back yet, so lets wait for it
                            return;
                        // call back must have happened, so lets iterate
                        continue;
                        
                    case IDLE:
                        // No more progress can be made.  Wait for another call to iterate
                        if (!_state.compareAndSet(State.ITERATING,State.IDLE))
                            throw new IllegalStateException("state="+_state.get());
                        return;
                        
                    case FAILED:
                        _state.set(State.FAILED);
                        return;
                        
                    default:
                        throw new IllegalStateException("state="+_state.get()+" next="+next);
                }
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
        if (_state.compareAndSet(State.ITERATING,State.IDLE))
            // then next loop will continue processing, so nothing to do here
            return;

        // OK do it properly
        loop: while(true)
        {
            switch(_state.get())
            {
                case ITERATING:
                    if (_state.compareAndSet(State.ITERATING,State.IDLE))
                        break loop;
                    continue;
                    
                case SCHEDULED:
                    if (_state.compareAndSet(State.SCHEDULED,State.IDLE))
                        iterate();
                    break loop;
                    
                case IDLE:
                    // TODO - remove this once old ICB usages updated
                    iterate();
                    break loop;
                    
                default:
                    throw new IllegalStateException(this+" state="+_state.get());
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
                    
                case SCHEDULED:
                    if (_state.compareAndSet(State.SCHEDULED,State.FAILED))
                        break loop;
                    continue;
                    
                case IDLE:
                    // TODO - remove this once old ICB usages updated
                    if (_state.compareAndSet(State.IDLE,State.FAILED))
                        break loop;
                    continue;

                default:
                    throw new IllegalStateException("state="+_state.get(),x);
            }
        }
    }

    public boolean isIdle()
    {
        return _state.get()==State.IDLE;
    }
    
    public boolean isFailed()
    {
        return _state.get()==State.FAILED;
    }
    
    public boolean isSucceeded()
    {
        return _state.get()==State.SUCCEEDED;
    }
}
