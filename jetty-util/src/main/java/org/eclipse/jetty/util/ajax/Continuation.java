// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.util.ajax;

import javax.servlet.ServletRequest;


/* ------------------------------------------------------------ */
/** Continuation.
 * 
 * A continuation is a mechanism by which a HTTP Request can be 
 * suspended and restarted after a timeout or an asynchronous event
 * has occured.
 * Blocking continuations will block the process of the request during a
 * call to {@link #suspend(long)}.
 * Non-blocking continuation can abort the current request and arrange for it 
 * to be retried when {@link #resume()} is called or the timeout expires.
 * 
 * In order to supprt non-blocking continuations, it is important that
 * all actions taken by a filter or servlet before a call to 
 * {@link #suspend(long)} are either idempotent (can be retried) or
 * are made conditional on {@link #isPending} so they are not performed on 
 * retried requests.
 * 
 * With the appropriate HTTP Connector, this allows threadless waiting
 * for events (see {@link org.eclipse.jetty.nio.SelectChannelConnector}).
 * 
 * 
 * @deprecated use {@link ServletRequest#suspend()}
 *
 */
public interface Continuation
{

    /* ------------------------------------------------------------ */
    /** Suspend handling.
     * This method will suspend the request for the timeout or until resume is
     * called.
     * @param timeout. A timeout of < 0 will cause an immediate return. I timeout of 0 will wait indefinitely.
     * @return True if resume called or false if timeout.
     */
    public boolean suspend(long timeout);
    
    /* ------------------------------------------------------------ */
    /** Resume the request.
     * Resume a suspended request.  The passed event will be returned in the getObject method.
     */
    public void resume();
    

    /* ------------------------------------------------------------ */
    /** Reset the continuation.
     * Cancel any pending status of the continuation.
     */
    public void reset();
    
    /* ------------------------------------------------------------ */
    /** Is this a newly created Continuation.
     * <p>
     * A newly created continuation has not had {@link #getEvent(long)} called on it.
     * </p>
     * @return True if the continuation has just been created and has not yet suspended the request.
     */
    public boolean isNew();
    
    /* ------------------------------------------------------------ */
    /** Get the pending status?
     * A continuation is pending while the handling of a call to suspend has not completed.
     * For blocking continuations, pending is true only during the call to {@link #suspend(long)}.
     * For non-blocking continuations, pending is true until a second call to {@link #suspend(long)} or 
     * a call to {@link #reset()}, thus this method can be used to determine if a request is being 
     * retried.
     * @return True if the continuation is handling a call to suspend.
     */
    public boolean isPending();
    
    /* ------------------------------------------------------------ */
    /** Get the resumed status?
     * @return True if the continuation is has been resumed.
     */
    public boolean isResumed();

    /* ------------------------------------------------------------ */
    /** Get the resumed status?
     * @return True if the continuation is has been expired.
     */
    public boolean isExpired();
    
    /* ------------------------------------------------------------ */
    /** Arbitrary object associated with the continuation for context.
     * @return An arbitrary object associated with the continuation
     */
    public Object getObject();
    
    /* ------------------------------------------------------------ */
    /** Arbitrary object associated with the continuation for context.
     * @param o An arbitrary object to associate with the continuation
     */
    public void setObject(Object o);
    
    /* ------------------------------------------------------------ */
    /** 
     */
    public void setMutex(Object mutex);
    
}
