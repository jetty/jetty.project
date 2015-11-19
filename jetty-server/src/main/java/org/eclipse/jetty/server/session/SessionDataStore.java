//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.server.session;

import java.util.Set;

import org.eclipse.jetty.util.component.LifeCycle;

/**
 * SessionDataStore
 *
 * A store for the data contained in a Session object. The store
 * would usually be persistent.
 */
public interface SessionDataStore extends LifeCycle
{
    /**
     * Initialize this session data store for the
     * given context. A SessionDataStore can only 
     * be used by one context.
     * 
     * @param contextId
     */
    void initialize(ContextId contextId);
    
    
    /**
     * Read in session data from storage
     * @param id
     * @return
     * @throws Exception
     */
    public SessionData load (String id) throws Exception;
    
    
    /**
     * Create a new SessionData 
     * @return
     */
    public SessionData newSessionData (String id, long created, long accessed, long lastAccessed, long maxInactiveMs);
    
    
    
    
    /**
     * Write out session data to storage
     * @param id
     * @param data
     * @throws Exception
     */
    public void store (String id, SessionData data) throws Exception;
    
    
    
    /**
     * Delete session data from storage
     * @param id
     * @return
     * @throws Exception
     */
    public boolean delete (String id) throws Exception;
    
    
    
   
    /**
     * Called periodically, this method should search the data store
     * for sessions that have been expired for a 'reasonable' amount 
     * of time. 
     * @param candidates if provided, these are keys of sessions that
     * the SessionStore thinks has expired and should be verified by the
     * SessionDataStore
     * @return
     */
    public Set<String> getExpired (Set<String> candidates);
    
    
}
