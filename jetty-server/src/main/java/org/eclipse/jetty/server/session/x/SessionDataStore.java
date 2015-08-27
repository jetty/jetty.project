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


package org.eclipse.jetty.server.session.x;

/**
 * SessionDataStore
 *
 * A (usually persistent) store for the data contained in a Session.
 */
public interface SessionDataStore
{
    
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
    
    
    
}
