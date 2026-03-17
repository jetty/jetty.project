//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.session;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * A base implementation of the {@link SessionCache} interface for managing a set of
 * Session objects pertaining to a context in memory.
 * </p>
 * <p>
 * This implementation ensures that multiple requests for the same session id
 * always return the same Session object.
 * </p>
 * <p>
 * It will delay writing out a session to the SessionDataStore until the
 * last request exits the session. If the SessionDataStore supports passivation
 * then the session passivation and activation listeners are called appropriately as
 * the session is written.
 * </p>
 * <p>
 * This implementation also supports evicting idle Session objects. An idle Session
 * is one that is still valid, has not expired, but has not been accessed by a
 * request for a configurable amount of time.  An idle session will be first
 * passivated before it is evicted from the cache.
 * </p>
 * <p>
 * Important note: Never {@link ManagedSession#lock() hold the lock} of any {@link ManagedSession}
 * while adding to/removing from/querying an instance of this class or else this might result in
 * a deadlock.
 * </p>
 */
@ManagedObject
public abstract class AbstractSessionCache extends ContainerLifeCycle implements SessionCache
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractSessionCache.class);

    /**
     * The authoritative source of session data
     */
    protected SessionDataStore _sessionDataStore;

    /**
     * The SessionManager related to this SessionCache
     */
    protected final SessionManager _manager;

    /**
     * Information about the context to which this SessionCache pertains
     */
    protected SessionContext _context;

    /**
     * When, if ever, to evict sessions: never; only when the last request for them finishes; after inactivity time (expressed as secs)
     */
    protected int _evictionPolicy = SessionCache.NEVER_EVICT;

    /**
     * If true, as soon as a new session is created, it will be persisted to the SessionDataStore
     */
    protected boolean _saveOnCreate = false;

    /**
     * If true, a session that will be evicted from the cache because it has been
     * inactive too long will be saved before being evicted.
     */
    protected boolean _saveOnInactiveEviction;

    /**
     * If true, a Session whose data cannot be read will be
     * deleted from the SessionDataStore.
     */
    protected boolean _removeUnloadableSessions;
    
    /**
     * If true, when a response is about to be committed back to the client,
     * a dirty session will be flushed to the session store.
     */
    protected boolean _flushOnResponseCommit = true;
    
    /**
     * If true, when the server shuts down, all sessions in the
     * cache will be invalidated before being removed.
     */
    protected boolean _invalidateOnShutdown;

    /**
     * Create a new Session object from pre-existing session data
     *
     * @param data the session data
     * @return a new Session object
     */
    @Override
    public abstract ManagedSession newSession(SessionData data);

    /**
     * Get the session matching the key from the cache. Does not load
     * the session.
     *
     * @param id session id
     * @return the Session object matching the id
     */
    protected abstract ManagedSession doGet(String id);

    /**
     * Compute the mappingFunction to create a Session object.
     * This method is expected to have precisely the same behaviour as
     * {@link java.util.concurrent.ConcurrentHashMap#compute} so that state changes
     * to both the session and cache can be effectively atomic to any thread using the cache.
     *
     * @param id the session id
     * @param mappingFunction the bi-function to compute the session
     * @return an existing Session from the cache, or null if the session was removed by the mapping function
     */
    protected ManagedSession doCompute(String id, BiFunction<String, ManagedSession, ManagedSession> mappingFunction)
    {
        // TODO Make this method abstract in next major release.
        throw new UnsupportedOperationException();
    }

    /**
     * Put the session into the map if it wasn't already there
     *
     * @param id the identity of the session
     * @param session the session object
     * @return null if the session wasn't already in the map, or the existing entry otherwise
     * @deprecated Replaced with {@link #doCompute(String, BiFunction)}
     */
    @Deprecated(forRemoval = true, since = "12.1.8")
    protected abstract Session doPutIfAbsent(String id, ManagedSession session);
    
    /**
     * Compute the mappingFunction to create a Session object iff the session 
     * with the given id isn't already in the map, otherwise return the existing Session.
     * This method is expected to have precisely the same behaviour as 
     * {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent} so that state changes
     * to both the session and cache can be effectively atomic to any thread using the cache.
     * 
     * @param id the session id
     * @param mappingFunction the function to load the data for the session
     * @return an existing Session from the cache
     * @deprecated Replaced with {@link #doCompute(String, BiFunction)}
     */
    @Deprecated(forRemoval = true, since = "12.1.8")
    protected abstract ManagedSession doComputeIfAbsent(String id, Function<String, ManagedSession> mappingFunction);

    /**
     * Replace the mapping from id to oldValue with newValue
     *
     * @param id the id
     * @param oldValue the old value
     * @param newValue the new value
     * @return true if replacement was done
     * @deprecated Replaced with {@link #doCompute(String, BiFunction)}
     */
    @Deprecated(forRemoval = true, since = "12.1.8")
    protected abstract boolean doReplace(String id, ManagedSession oldValue, ManagedSession newValue);

    /**
     * Remove the session with this identity from the store
     *
     * @param id the id
     * @return Session that was removed or null
     * @deprecated Replaced with {@link #doCompute(String, BiFunction)}
     */
    @Deprecated(forRemoval = true, since = "12.1.8")
    public abstract ManagedSession doDelete(String id);

    /**
     * @param handler the {@link SessionManager} to use
     */
    public AbstractSessionCache(SessionManager handler)
    {
        _manager = handler;
    }

    /**
     * @return the SessionManger
     */
    @Override
    public SessionManager getSessionManager()
    {
        return _manager;
    }

    @Override
    public void initialize(SessionContext context)
    {
        if (isStarted())
            throw new IllegalStateException("Context set after session store started");
        _context = context;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_sessionDataStore == null)
            throw new IllegalStateException("No session data store configured");

        if (_manager == null)
            throw new IllegalStateException("No session manager");

        if (_context == null)
            throw new IllegalStateException("No ContextId");

        _sessionDataStore.initialize(_context);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        _sessionDataStore.stop();
        super.doStop();
    }

    /**
     * @return the SessionDataStore or null if there isn't one
     */
    @Override
    public SessionDataStore getSessionDataStore()
    {
        return _sessionDataStore;
    }

    @Override
    public void setSessionDataStore(SessionDataStore sessionStore)
    {
        updateBean(_sessionDataStore, sessionStore);
        _sessionDataStore = sessionStore;
    }

    @ManagedAttribute(value = "session eviction policy", readonly = true)
    @Override
    public int getEvictionPolicy()
    {
        return _evictionPolicy;
    }

    /**
     * -1 means we never evict inactive sessions.
     * 0 means we evict a session after the last request for it exits
     * &gt;0 is the number of seconds after which we evict inactive sessions from the cache
     *
     */
    @Override
    public void setEvictionPolicy(int evictionTimeout)
    {
        _evictionPolicy = evictionTimeout;
    }

    @ManagedAttribute(value = "immediately save new sessions", readonly = true)
    @Override
    public boolean isSaveOnCreate()
    {
        return _saveOnCreate;
    }

    @Override
    public void setSaveOnCreate(boolean saveOnCreate)
    {
        _saveOnCreate = saveOnCreate;
    }

    /**
     * @return true if sessions that can't be loaded are deleted from the store
     */
    @ManagedAttribute(value = "delete unreadable stored sessions", readonly = true)
    @Override
    public boolean isRemoveUnloadableSessions()
    {
        return _removeUnloadableSessions;
    }

    /**
     * If a session's data cannot be loaded from the store without error, remove
     * it from the persistent store.
     *
     * @param removeUnloadableSessions if <code>true</code> unloadable sessions will be removed from session store
     */
    @Override
    public void setRemoveUnloadableSessions(boolean removeUnloadableSessions)
    {
        _removeUnloadableSessions = removeUnloadableSessions;
    }

    @Override
    public void setFlushOnResponseCommit(boolean flushOnResponseCommit)
    {
        _flushOnResponseCommit = flushOnResponseCommit;
    }

    @Override
    public boolean isFlushOnResponseCommit()
    {
        return _flushOnResponseCommit;
    }

    /**
     * Get a session object.
     *
     * If the session object is not in this session store, try getting
     * the data for it from a SessionDataStore associated with the
     * session manager. The usage count of the session is incremented.
     *
     */
    @Override
    public ManagedSession get(String id) throws Exception
    {
        return getAndEnter(id, true);
    }

    /** Get a session object.
     *
     * If the session object is not in this session store, try getting
     * the data for it from a SessionDataStore associated with the
     * session manager.
     * 
     * @param id The session to retrieve
     * @param enter if true, the usage count of the session will be incremented
     * @return the session if it exists either in the cache or the store, null otherwise
     * @throws Exception if the session cannot be loaded
     */
    protected ManagedSession getAndEnter(String id, boolean enter) throws Exception
    {
        AtomicReference<Exception> exception = new AtomicReference<>();
        ManagedSession session = doCompute(id, (k, v) ->
        {
            try
            {
                if (v == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} not found locally in {}, attempting to load", k, this);
                    v = loadSession(k);
                }
                if (v != null)
                {
                    try (AutoLock ignore = v.lock())
                    {
                        v.setResident(true); //ensure freshly loaded session is resident
                        if (enter)
                            v.use();
                    }
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} not loaded by store", k);
                }
                return v;
            }
            catch (Exception e)
            {
                exception.set(e);
                return null;
            }
        });

        Exception ex = exception.get();
        if (ex != null)
            throw ex;

        return session;
    }

    /**
     * Load the info for the session from the session data store
     *
     * @param id the id
     * @return a Session object filled with data or null if the session doesn't exist
     */
    private ManagedSession loadSession(String id)
        throws Exception
    {
        SessionData data = null;
        ManagedSession session = null;

        if (_sessionDataStore == null)
            return null; //can't load it

        try
        {
            data = _sessionDataStore.load(id);

            if (data == null) //session doesn't exist
                return null;

            data.setLastNode(_context.getWorkerName()); //we are going to manage the node
            session = newSession(data);
            return session;
        }
        catch (UnreadableSessionDataException e)
        {
            //can't load the session, delete it
            if (isRemoveUnloadableSessions())
                _sessionDataStore.delete(id);
            throw e;
        }
    }

    /**
     * Add an entirely new session (created by the application calling Request.getSession(true))
     * to the cache. The usage count of the fresh session is incremented.
     * 
     * @param id the id
     * @param session the session
     */
    @Override
    public void add(String id, ManagedSession session) throws Exception
    {
        if (id == null || session == null)
            throw new IllegalArgumentException("Add key=" + id + " session=" + (session == null ? "null" : session.getId()));

        if (session.getSessionManager() == null)
            throw new IllegalStateException("Session " + id + " is not managed");

        if (!session.isValid())
            throw new IllegalStateException("Session " + id + " is not valid");

        doCompute(id, (k, v) ->
        {
            if (v != null)
                throw new IllegalStateException("Session " + k + " already in cache");
            try (AutoLock ignore = session.lock())
            {
                session.setResident(true); //its in the cache
                session.use(); //the request is using it
                return session;
            }
        });
    }

    /**
     * A response that has accessed this session is about to
     * be returned to the client. Pass the session to the store
     * to persist, so that any changes will be visible to
     * subsequent requests on the same node (if using NullSessionCache),
     * or on other nodes.
     */
    @Override
    public void commit(ManagedSession session) throws Exception
    {
        if (session == null)
            return;

        try (AutoLock ignore = session.lock())
        {
            //only write the session out at this point if the attributes changed. If only
            //the lastAccess/expiry time changed defer the write until the last request exits
            if (session.isValid() && session.getSessionData().isDirty() && _flushOnResponseCommit)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Flush session {} on response commit", session);
                //save the session
                if (!_sessionDataStore.isPassivating())
                {
                    _sessionDataStore.store(session.getId(), session.getSessionData());
                }
                else
                {
                    session.onSessionPassivation();
                    _sessionDataStore.store(session.getId(), session.getSessionData());
                    session.onSessionActivation();
                }
            }
        }
    }

    /**
     * Finish using the Session object.
     *
     * This should be called when a request exists the session. Only when the last
     * simultaneous request exists the session will any action be taken.
     *
     * If there is a SessionDataStore write the  session data through to it.
     *
     * If the SessionDataStore supports passivation, call the passivate/active listeners.
     *
     * If the evictionPolicy == SessionCache.EVICT_ON_SESSION_EXIT then after we have saved
     * the session, we evict it from the cache.
     *
     */
    @Override
    public void release(ManagedSession session) throws Exception
    {
        if (session == null || session.getId() == null)
            throw new IllegalArgumentException((session == null ? "Null session" : "Null session id"));

        String id = session.getId();
        
        if (session.getSessionManager() == null)
            throw new IllegalStateException("Session " + id + " is not managed");

        AtomicReference<Exception> exception = new AtomicReference<>();
        doCompute(id, (k, v) ->
        {
            try (AutoLock ignore = session.lock())
            {
                if (session.isInvalidOrInvalidating())
                    return v;

                session.release();

                //don't do anything with the session until the last request for it has finished
                if ((session.getRequests() <= 0))
                {
                    //save the session
                    if (!_sessionDataStore.isPassivating())
                    {
                        //if our backing datastore isn't the passivating kind, just save the session
                        _sessionDataStore.store(k, session.getSessionData());
                        //if we evict on session exit, boot it from the cache
                        if (getEvictionPolicy() == EVICT_ON_SESSION_EXIT)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Eviction on request exit id={}", k);
                            session.setResident(false);
                            return null; // remove from map
                        }
                        else
                        {
                            session.setResident(true);
                            if (LOG.isDebugEnabled())
                                LOG.debug("Non passivating SessionDataStore, session in SessionCache only id={}", k);
                            return session; //ensure it is in our map
                        }
                    }
                    else
                    {
                        //backing store supports passivation, call the listeners
                        session.onSessionPassivation();
                        if (LOG.isDebugEnabled())
                            LOG.debug("Session passivating id={}", k);
                        _sessionDataStore.store(k, session.getSessionData());

                        if (getEvictionPolicy() == EVICT_ON_SESSION_EXIT)
                        {
                            //throw out the passivated session object from the map
                            session.setResident(false);
                            if (LOG.isDebugEnabled())
                                LOG.debug("Evicted on request exit id={}", k);
                            return null; // remove from map
                        }
                        else
                        {
                            //reactivate the session
                            session.onSessionActivation();
                            session.setResident(true);
                            if (LOG.isDebugEnabled())
                                LOG.debug("Session reactivated id={}", k);
                            return session; //ensure it is in our map
                        }
                    }
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Req count={} for id={}", session.getRequests(), k);
                    session.setResident(true);
                    return session; //ensure it is the map, but don't save it to the backing store until the last request exists
                }
            }
            catch (Exception e)
            {
                exception.set(e);
                return v;
            }
        });

        Exception ex = exception.get();
        if (ex != null)
            throw ex;
    }

    /**
     * Check to see if a session corresponding to the id exists.
     *
     * This method will first check with the object store. If it
     * doesn't exist in the object store (might be passivated etc),
     * it will check with the data store.
     *
     * @throws Exception the Exception
     */
    @Override
    public boolean exists(String id) throws Exception
    {
        //try the object store first
        ManagedSession s = doGet(id);
        if (s != null)
        {
            //wait for the lock and check the validity of the session
            return s.isValid();
        }

        //not there, so find out if session data exists for it
        return _sessionDataStore.exists(id);
    }

    /**
     * Check to see if this cache contains an entry for the session
     * corresponding to the session id.
     *
     */
    @Override
    public boolean contains(String id) throws Exception
    {
        //just ask our object cache, not the store
        return (doGet(id) != null);
    }

    /**
     * Remove a session object from this store and from any backing store.
     *
     */
    @Override
    public ManagedSession delete(String id) throws Exception
    {
        //get the session, if its not in memory, this will load it
        AtomicReference<ManagedSession> session = new AtomicReference<>();
        AtomicReference<Exception> exception = new AtomicReference<>();
        doCompute(id, (k, v) ->
        {
            try
            {
                if (v == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} not found locally in {}, attempting to load", k, this);
                    v = loadSession(k);
                }
                session.set(v);
                if (v == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} not loaded by store", k);
                }

                //Always delete it from the backing data store
                if (_sessionDataStore != null)
                {
                    boolean dsdel = _sessionDataStore.delete(k);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session id={} deleted in session data store {}", k, dsdel);
                }

                //delete it from the session object store
                if (v != null)
                    v.setResident(false);
                return null;
            }
            catch (Exception e)
            {
                exception.set(e);
            }
            return v;
        });

        Exception ex = exception.get();
        if (ex != null)
            throw ex;

        return session.get();
    }

    @Override
    public Set<String> checkExpiration(Set<String> candidates)
    {
        if (!isStarted())
            return Collections.emptySet();

        if (LOG.isDebugEnabled())
            LOG.debug("{} checking expiration on {}", this, candidates);
        Set<String> allCandidates = _sessionDataStore.getExpired(candidates);
        Set<String> sessionsInUse = new HashSet<>();
        if (allCandidates != null)
        {
            for (String c : allCandidates)
            {
                ManagedSession s = doGet(c);
                if (s != null && s.getRequests() > 0) //if the session is in my cache, check its not in use first
                    sessionsInUse.add(c);
            }
            try
            {
                allCandidates.removeAll(sessionsInUse);
            }
            catch (UnsupportedOperationException e)
            {
                Set<String> tmp = new HashSet<>(allCandidates);
                tmp.removeAll(sessionsInUse);
                allCandidates = tmp;
            }
        }
        return allCandidates;
    }

    /**
     * Check a session for being inactive and
     * thus being able to be evicted, if eviction
     * is enabled.
     *
     * @param session session to check
     */
    @Override
    public void checkInactiveSession(ManagedSession session)
    {
        if (session == null)
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("Checking for idle {}", session.getId());

        doCompute(session.getId(), (k, v) ->
        {
            try (AutoLock ignore = session.lock())
            {
                if (getEvictionPolicy() > 0 && session.isIdleLongerThan(getEvictionPolicy()) &&
                    session.isValid() && session.isResident() && session.getRequests() <= 0)
                {
                    //Be careful with saveOnInactiveEviction - you may be able to re-animate a session that was
                    //being managed on another node and has expired.
                    try
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Evicting idle session {}", session.getId());

                        //save before evicting
                        if (isSaveOnInactiveEviction() && _sessionDataStore != null)
                        {
                            if (_sessionDataStore.isPassivating())
                                session.onSessionPassivation();

                            //Fake being dirty to force the write
                            session.getSessionData().setDirty(true);
                            _sessionDataStore.store(session.getId(), session.getSessionData());
                        }

                        session.setResident(false);
                        return null; //detach from this cache
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Passivation of idle session {} failed", session.getId(), e);
                    }
                }
                return v;
            }
        });
    }

    @Override
    public ManagedSession renewSessionId(String oldId, String newId, String oldExtendedId, String newExtendedId)
        throws Exception
    {
        if (StringUtil.isBlank(oldId))
            throw new IllegalArgumentException("Old session id is null");
        if (StringUtil.isBlank(newId))
            throw new IllegalArgumentException("New session id is null");

        AtomicReference<ManagedSession> loadedSessionRef = new AtomicReference<>();
        AtomicReference<Exception> exception = new AtomicReference<>();
        // Find the old session and remove it from both the store and the cache.
        doCompute(oldId, (k, v) ->
        {
            try
            {
                if (v == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} not found locally in {}, attempting to load", k, this);
                    v = loadSession(k);
                }
                if (v == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} not loaded by store", k);
                    return null;
                }

                try (AutoLock ignore = v.lock())
                {
                    v.setResident(true); //ensure freshly loaded session is resident
                    v.use();
                    v.checkValidForWrite(); //can't change id on invalid session
                    v.getSessionData().setId(newId);
                    v.getSessionData().setLastSaved(0); //pretend that the session has never been saved before to get a full save
                    v.getSessionData().setDirty(true);  //ensure we will try to write the session out
                    v.setExtendedId(newExtendedId); //remember the new extended id
                    v.onIdChanged(); //session id changed

                    if (_sessionDataStore != null)
                        _sessionDataStore.delete(k); //delete the session data with the old id
                    loadedSessionRef.set(v);
                }
                return null;
            }
            catch (Exception e)
            {
                exception.set(e);
                return null;
            }
        });

        Exception ex = exception.get();
        if (ex != null)
            throw ex;

        // Install the new session both in the store and the cache.
        ManagedSession session = doCompute(newId, (k, v) ->
        {
            if (v != null)
                throw new IllegalStateException("Duplicate session id: " + k);

            ManagedSession loadedSession = loadedSessionRef.get();
            try (AutoLock ignore = loadedSession.lock())
            {
                if (_sessionDataStore != null)
                    _sessionDataStore.store(k, loadedSession.getSessionData()); //save the session data with the new id
                if (LOG.isDebugEnabled())
                    LOG.debug("Session id={} swapped for new id={}", oldId, k);
                return loadedSession;
            }
            catch (Exception e)
            {
                exception.set(e);
                return null;
            }
        });

        ex = exception.get();
        if (ex != null)
            throw ex;

        return session;
    }

    /**
     * Swap the id on a session.
     *
     * @param session the session for which to do the swap
     * @param newId the new id
     * @param newExtendedId the full id plus node id
     * @throws Exception if there was a failure saving the change
     * @deprecated Use {@link #renewSessionId(String, String, String, String)} instead as the session retrieval must
     * happen atomically with its renewal.
     */
    @Deprecated(since = "12.1.8", forRemoval = true)
    protected void renewSessionId(ManagedSession session, String newId, String newExtendedId)
        throws Exception
    {
        if (session == null)
            return;

        String oldId = session.getId();
        AtomicReference<Exception> exception = new AtomicReference<>();
        doCompute(oldId, (k, v) ->
        {
            try (AutoLock ignore = session.lock())
            {
                if (!session.isResident())
                    return null; // session has been removed between is retrieval and this renewSessionId call

                session.checkValidForWrite(); //can't change id on invalid session
                session.getSessionData().setId(newId);
                session.getSessionData().setLastSaved(0); //pretend that the session has never been saved before to get a full save
                session.getSessionData().setDirty(true);  //ensure we will try to write the session out
                session.setExtendedId(newExtendedId); //remember the new extended id
                session.onIdChanged(); //session id changed
                session.setResident(false);

                try
                {
                    if (_sessionDataStore != null)
                        _sessionDataStore.delete(k); //delete the session data with the old id
                }
                catch (Exception e)
                {
                    exception.set(e);
                }
                return null;
            }
        });

        Exception ex = exception.get();
        if (ex != null)
            throw ex;

        doCompute(newId, (k, v) ->
        {
            if (v != null)
                throw new IllegalStateException("Duplicate session id: " + k);

            try (AutoLock ignore = session.lock())
            {
                session.setResident(true);
                if (_sessionDataStore != null)
                    _sessionDataStore.store(k, session.getSessionData()); //save the session data with the new id
                if (LOG.isDebugEnabled())
                    LOG.debug("Session id={} swapped for new id={}", oldId, k);
            }
            catch (Exception e)
            {
                exception.set(e);
            }
            return session;
        });

        ex = exception.get();
        if (ex != null)
            throw ex;
    }

    @Override
    public void setSaveOnInactiveEviction(boolean saveOnEvict)
    {
        _saveOnInactiveEviction = saveOnEvict;
    }

    @Override
    public void setInvalidateOnShutdown(boolean invalidateOnShutdown)
    {
        _invalidateOnShutdown = invalidateOnShutdown;
    }

    @Override
    public boolean isInvalidateOnShutdown()
    {
        return _invalidateOnShutdown;
    }

    /**
     * Whether we should save a session that has been inactive before
     * we boot it from the cache.
     *
     * @return true if an inactive session will be saved before being evicted
     */
    @ManagedAttribute(value = "save sessions before evicting from cache", readonly = true)
    @Override
    public boolean isSaveOnInactiveEviction()
    {
        return _saveOnInactiveEviction;
    }

    @Override
    public ManagedSession newSession(String id, long time, long maxInactiveMs)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Creating new session id={}", id);
        ManagedSession session = newSession(_sessionDataStore.newSessionData(id, time, time, time, maxInactiveMs));
        session.getSessionData().setLastNode(_context.getWorkerName());
        try
        {
            if (isSaveOnCreate() && _sessionDataStore != null)
                _sessionDataStore.store(id, session.getSessionData());
        }
        catch (Exception e)
        {
            LOG.warn("Save of new session {} failed", id, e);
        }
        return session;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[evict=%d,removeUnloadable=%b,saveOnCreate=%b,saveOnInactiveEvict=%b]",
            TypeUtil.toShortName(this.getClass()), this.hashCode(), _evictionPolicy,
            _removeUnloadableSessions, _saveOnCreate, _saveOnInactiveEviction);
    }
}
