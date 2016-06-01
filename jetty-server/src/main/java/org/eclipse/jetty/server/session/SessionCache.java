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

import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.component.LifeCycle;

/**
 * SessionCache
 *
 * A set of Session objects for a context that are actively being
 * managed by this context instance. 
 * 
 * Multiple requests for the same session id on the same context should always 
 * share the same Session object from the SessionCache.
 * 
 * The data for the Session objects is obtained from, and written to a SessionDataStore. 
 * It is assumed that the SessionDataStore is the authoritative source of session data:
 * <ul>
 * <li>if the session data is not present in the SessionDataStore the session does not exist.</li>
 * <li>if the session data is present in the SessionDataStore but its expiry time has passed then 
 * the session is deemed to have expired</li>
 *</ul>
 * 
 * Examples of SessionDataStores are relational or nosql databases, filesystems, or other
 * distributed mechanisms.
 * 
 * A SessionCache is optionally able to passivate a managed Session to the SessionDataStore and 
 * evict it from the cache if it has been in memory, but not accessed for a configurable amount of time.
 * 
 * Implementations of the SessionCache may also implement different strategies for writing out
 * Session data to the SessionDataStore.
 */
public interface SessionCache extends LifeCycle
{
    public static final int NEVER_EVICT = -1;
    public static final int EVICT_ON_SESSION_EXIT = 0;
    public static final int EVICT_ON_INACTIVITY = 1; //any number equal or greater is time in seconds
    
    
    
    void initialize(SessionContext context);
    SessionHandler getSessionHandler();
    Session newSession (HttpServletRequest request, String id,  long time, long maxInactiveMs);
    Session newSession (SessionData data);
    Session renewSessionId (String oldId, String newId) throws Exception;
    Session get(String id) throws Exception;
    void put(String id, Session session) throws Exception;
    boolean contains (String id) throws Exception;
    boolean exists (String id) throws Exception;
    Session delete (String id) throws Exception;
    void shutdown ();
    Set<String> checkExpiration (Set<String> candidates);
    SessionDataStore getSessionDataStore();
    void setSessionDataStore(SessionDataStore sds);
    void checkInactiveSession(Session session);  
    void setEvictionPolicy (int policy);
    int getEvictionPolicy ();
    void setSaveOnInactiveEviction (boolean saveOnEvict);
    boolean isSaveOnInactiveEviction ();
    void setSaveOnCreate(boolean saveOnCreate);
    boolean isSaveOnCreate();
    void setRemoveUnloadableSessions(boolean removeUnloadableSessions);
    boolean isRemoveUnloadableSessions();
}
