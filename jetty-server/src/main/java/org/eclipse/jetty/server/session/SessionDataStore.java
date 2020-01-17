//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

/**
 * SessionDataStore
 *
 * A store for the data contained in a Session object. The store
 * would usually be persistent.
 */
public interface SessionDataStore extends SessionDataMap
{

    /**
     * Create a new SessionData
     *
     * @param id the id
     * @param created the timestamp when created
     * @param accessed the timestamp when accessed
     * @param lastAccessed the timestamp when last accessed
     * @param maxInactiveMs the max inactive time in milliseconds
     * @return a new SessionData object
     */
    SessionData newSessionData(String id, long created, long accessed, long lastAccessed, long maxInactiveMs);

    /**
     * Called periodically, this method should search the data store
     * for sessions that have been expired for a 'reasonable' amount
     * of time.
     *
     * @param candidates if provided, these are keys of sessions that
     * the SessionDataStore thinks has expired and should be verified by the
     * SessionDataStore
     * @return set of session ids
     */
    Set<String> getExpired(Set<String> candidates);

    /**
     * True if this type of datastore will passivate session objects
     *
     * @return true if this store can passivate sessions, false otherwise
     */
    boolean isPassivating();

    /**
     * Test if data exists for a given session id.
     *
     * @param id Identity of session whose existence should be checked
     * @return true if valid, non-expired session exists
     * @throws Exception if problem checking existence with persistence layer
     */
    boolean exists(String id) throws Exception;
}
