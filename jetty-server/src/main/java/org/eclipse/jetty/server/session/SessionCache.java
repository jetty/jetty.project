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
 * A working set of {@link Session} objects for a context.
 * 
 * Ideally, multiple requests for the same session id in the same context will always 
 * share the same Session object from the SessionCache, but it would be possible
 * for implementations of SessionCache to create a fresh object for each request.
 * 
 * The SessionData pertaining to the Session objects is obtained from/written to a SessionDataStore. 
 * The SessionDataStore is the authoritative source of session data:
 * <ul>
 * <li>if the session data is not present in the SessionDataStore the session does not exist.</li>
 * <li>if the session data is present in the SessionDataStore but its expiry time has passed then 
 * the session is deemed to have expired and is therefore invalid</li>
 *</ul>
 * 
 * A SessionCache can passivate a valid Session to the SessionDataStore and 
 * evict it from the cache according to various strategies:
 * <ul>
 *  <li>whenever the last request exits a Session</li>
 *  <li>whenever the Session has not been accessed for a configurable number of seconds</li>
 * </ul>. 
 * 
 * Eviction can save memory, and can also help mitigate
 * some of the problems of a non-sticky load balancer by forcing the session data to
 * be re-read from the SessionDataStore more frequently. 
 * 
 */
public interface SessionCache extends LifeCycle
{
    public static final int NEVER_EVICT = -1;
    public static final int EVICT_ON_SESSION_EXIT = 0;
    public static final int EVICT_ON_INACTIVITY = 1; //any number equal or greater is time in seconds
    
    
    
    /**
     * @param context
     */
    void initialize(SessionContext context);
    
    void shutdown ();
    
    
    SessionHandler getSessionHandler();
    
    
    /**
     * Create an entirely new Session.
     * 
     * @param request
     * @param id
     * @param time
     * @param maxInactiveMs
     * @return a new Session 
     */
    Session newSession (HttpServletRequest request, String id,  long time, long maxInactiveMs);
    
    /**
     * Re-materialize a Session that has previously existed.
     * @param data
     * @return a Session object for the data supplied
     */
    Session newSession (SessionData data);
    
    
    /**
     * Change the id of a Session.
     * 
     * @param oldId
     * @param newId
     * @return the Session after changing its id
     * @throws Exception
     */
    Session renewSessionId (String oldId, String newId) throws Exception;
    
    
    /**
     * Get an existing Session. If necessary, the cache will load the data for
     * the session from the configured SessionDataStore.
     * 
     * @param id
     * @return the Session if one exists, null otherwise
     * @throws Exception
     */
    Session get(String id) throws Exception;
    
    
    /**
     * Finish using a Session. This is called by the SessionHandler
     * once a request is finished with a Session. SessionCache
     * implementations may want to delay writing out Session contents
     * until the last request exits a Session.
     * 
     * @param id
     * @param session
     * @throws Exception
     */
    void put(String id, Session session) throws Exception;
    
    
    /**
     * Check to see if a Session is in the cache. Does NOT consult
     * the SessionDataStore.
     * 
     * @param id
     * @return true if a Session object matching the id is present
     * in the cache, false otherwise
     * @throws Exception
     */
    boolean contains (String id) throws Exception;
    
    
    /**
     * Check to see if a session exists: WILL consult the
     * SessionDataStore.
     * 
     * @param id
     * @return true if the session exists, false otherwise
     * @throws Exception
     */
    boolean exists (String id) throws Exception;
    
    
    /**
     * Remove a Session completely: from both this 
     * cache and the SessionDataStore.
     * 
     * @param id
     * @return the Session that was removed, null otherwise
     * @throws Exception
     */
    Session delete (String id) throws Exception;

    
    /**
     * Check a list of session ids that belong to potentially expired
     * sessions. The Session in the cache should be checked,
     * but also the SessionDataStore, as that is the authoritative
     * source of all session information.
     * 
     * @param candidates the session ids to check
     * @return the set of session ids that have actually expired: this can 
     * be a superset of the original candidate list.
     */
    Set<String> checkExpiration (Set<String> candidates);
    
    /**
     * Check a Session to see if it might be appropriate to
     * evict or expire.
     * 
     * @param session
     */
    void checkInactiveSession(Session session);  
    
    
    /**
     * A SessionDataStore that is the authoritative source
     * of session information.
     * @param sds
     */
    void setSessionDataStore(SessionDataStore sds);
    SessionDataStore getSessionDataStore();

    
    /**
     * Sessions in this cache can be: 
     * <ul>
     * <li>never evicted</li>
     * <li>evicted once the last request exits</li>
     * <li>evicted after a configurable period of inactivity</li>
     * </ul>
     * 
     * @param policy -1 is never evict; 0 is evict-on-exit; and any other positive
     * value is the time in seconds that a session can be idle before it can
     * be evicted.
     */
    void setEvictionPolicy (int policy);
    int getEvictionPolicy ();
    
    /**
     * Whether or not a a session that is about to be evicted should
     * be saved before being evicted. 
     * 
     * @param saveOnEvict
     */
    void setSaveOnInactiveEviction (boolean saveOnEvict);
    boolean isSaveOnInactiveEviction ();
    
    
    /**
     * Whether or not a session that is newly created should be
     * immediately saved. If false, a session that is created and
     * invalidated within a single request is never persisted.
     * 
     * @param saveOnCreate
     */
    void setSaveOnCreate(boolean saveOnCreate);
    boolean isSaveOnCreate();
    
    
    /**
     * If the data for a session exists but is unreadable, 
     * the SessionCache can instruct the SessionDataStore to delete it.
     * 
     * @param removeUnloadableSessions
     */
    void setRemoveUnloadableSessions(boolean removeUnloadableSessions);
    boolean isRemoveUnloadableSessions();
}
