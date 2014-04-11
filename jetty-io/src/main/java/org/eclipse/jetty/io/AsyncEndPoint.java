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

package org.eclipse.jetty.io;

import org.eclipse.jetty.util.thread.Timeout;

public interface AsyncEndPoint extends ConnectedEndPoint
{
    /* ------------------------------------------------------------ */
    /**
     * Dispatch the endpoint if it is not already dispatched
     * 
     */
    public void dispatch();
    
    /* ------------------------------------------------------------ */
    /**
     * Dispatch the endpoint. If it is already dispatched, schedule a redispatch
     * 
     */
    public void asyncDispatch();
    
    /* ------------------------------------------------------------ */
    /** Schedule a write dispatch.
     * Set the endpoint to not be writable and schedule a dispatch when
     * it becomes writable.
     */
    public void scheduleWrite();  

    /* ------------------------------------------------------------ */
    /** Callback when idle.
     * <p>An endpoint is idle if there has been no IO activity for 
     * {@link #getMaxIdleTime()} and {@link #isCheckForIdle()} is true.
     * @param idleForMs TODO
     */
    public void onIdleExpired(long idleForMs);

    /* ------------------------------------------------------------ */
    /** Set if the endpoint should be checked for idleness
     */
    public void setCheckForIdle(boolean check);

    /* ------------------------------------------------------------ */
    /** Get if the endpoint should be checked for idleness
     */
    public boolean isCheckForIdle();

    
    /* ------------------------------------------------------------ */
    public boolean isWritable();

    /* ------------------------------------------------------------ */
    /**
     * @return True if IO has been successfully performed since the last call to {@link #hasProgressed()}
     */
    public boolean hasProgressed();
    
    /* ------------------------------------------------------------ */
    /**
     */
    public void scheduleTimeout(Timeout.Task task, long timeoutMs);

    /* ------------------------------------------------------------ */
    /**
     */
    public void cancelTimeout(Timeout.Task task);
}
