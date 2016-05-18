//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

/**
 * SessionDataMap
 *
 * A map style access to SessionData keyed by the session id.
 */
public interface SessionDataMap
{
    /**
     * Initialize this session data store for the
     * given context. A SessionDataStore can only 
     * be used by one context(/session manager).
     * 
     * @param context context associated
     */
    void initialize(SessionContext context);
    
    
    /**
     * Read in session data from storage
     * @param id identity of session to load
     * @return the SessionData matching the id
     * @throws Exception if unable to load session data
     */
    public SessionData load (String id) throws Exception;
    
  
    
    /**
     * Write out session data to storage
     * @param id identity of session to store
     * @param data info of session to store
     * @throws Exception if unable to write session data
     */
    public void store (String id, SessionData data) throws Exception;
    
    
    
    /**
     * Delete session data from storage
     * @param id identity of session to delete
     * @return true if the session was deleted from the permanent store
     * @throws Exception if unable to delete session data
     */
    public boolean delete (String id) throws Exception;
    
    
}