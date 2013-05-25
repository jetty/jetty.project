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

import java.util.concurrent.atomic.AtomicBoolean;


/* ------------------------------------------------------------ */
/** Iterating Callback.
 * <p>This specialized callback is used when breaking up an
 * asynchronous task into smaller asynchronous tasks.  A typical pattern
 * is that a successful callback is used to schedule the next sub task, but 
 * if that task completes quickly and uses the calling thread to callback
 * the success notification, this can result in a growing stack depth.
 * </p>
 * <p>To avoid this issue, this callback uses an AtomicBoolean to note 
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
    private final AtomicBoolean _iterating = new AtomicBoolean();
    
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
            while(_iterating.compareAndSet(false,true))
            {
                // process and test if we are complete
                if (process())
                {
                    completed();
                    return;
                }
            }
        }
        catch(Exception e)
        {
            _iterating.set(false);
            failed(e);
        }
        finally
        {
            _iterating.set(false);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void succeeded()
    {
        if (!_iterating.compareAndSet(true,false))
            iterate();
    }
}
