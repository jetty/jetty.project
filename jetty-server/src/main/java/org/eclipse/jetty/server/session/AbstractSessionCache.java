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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Locker.Lock;

/**
 * AbstractSessionCache
 *
 * A base implementation of the {@link SessionCache} interface for managing a set of
 * Session objects pertaining to a context in memory.
 *
 * This implementation ensures that multiple requests for the same session id
 * always return the same Session object.
 *
 * It will delay writing out a session to the SessionDataStore until the
 * last request exits the session. If the SessionDataStore supports passivation
 * then the session passivation and activation listeners are called appropriately as
 * the session is written.
 *
 * This implementation also supports evicting idle Session objects. An idle Session
 * is one that is still valid, has not expired, but has not been accessed by a
 * request for a configurable amount of time.  An idle session will be first
 * passivated before it is evicted from the cache.
 */
@ManagedObject
public abstract class AbstractSessionCache extends ContainerLifeCycle implements SessionCache
{
    static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

    /**
     * The authoritative source of session data
     */
    protected SessionDataStore _sessionDataStore;

    /**
     * The SessionHandler related to this SessionCache
     */
    protected final SessionHandler _handler;

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
    protected boolean _flushOnResponseCommit;
    
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
    public abstract Session newSession(SessionData data);

    /**
     * Create a new Session for a request.
     *
     * @param request the request
     * @param data the session data
     * @return the new session
     */
    public abstract Session newSession(HttpServletRequest request, SessionData data);

    /**
     * Get the session matching the key from the cache. Does not load
     * the session.
     *
     * @param id session id
     * @return the Session object matching the id
     */
    protected abstract Session doGet(String id);

    /**
     * Put the session into the map if it wasn't already there
     *
     * @param id the identity of the session
     * @param session the session object
     * @return null if the session wasn't already in the map, or the existing entry otherwise
     */
    protected abstract Session doPutIfAbsent(String id, Session session);
    
    /**
     * Compute the mappingFunction to create a Session object iff the session 
     * with the given id isn't already in the map, otherwise return the existing Session.
     * This method is expected to have precisely the same behaviour as 
     * {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent}
     * 
     * @param id the session id
     * @param mappingFunction the function to load the data for the session
     * @return an existing Session from the cache
     */
    protected abstract Session doComputeIfAbsent(String id, Function<String, Session> mappingFunction);

    /**
     * Replace the mapping from id to oldValue with newValue
     *
     * @param id the id
     * @param oldValue the old value
     * @param newValue the new value
     * @return true if replacement was done
     */
    protected abstract boolean doReplace(String id, Session oldValue, Session newValue);

    /**
     * Remove the session with this identity from the store
     *
     * @param id the id
     * @return Session that was removed or null
     */
    public abstract Session doDelete(String id);

    /**
     * @param handler the {@link SessionHandler} to use
     */
    public AbstractSessionCache(SessionHandler handler)
    {
        _handler = handler;
    }

    /**
     * @return the SessionManger
     */
    @Override
    public SessionHandler getSessionHandler()
    {
        return _handler;
    }

    /**
     * @see org.eclipse.jetty.server.session.SessionCache#initialize(org.eclipse.jetty.server.session.SessionContext)
     */
    @Override
    public void initialize(SessionContext context)
    {
        if (isStarted())
            throw new IllegalStateException("Context set after session store started");
        _context = context;
    }

    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        if (_sessionDataStore == null)
            throw new IllegalStateException("No session data store configured");

        if (_handler == null)
            throw new IllegalStateException("No session manager");

        if (_context == null)
            throw new IllegalStateException("No ContextId");

        _sessionDataStore.initialize(_context);
        super.doStart();
    }

    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
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

    /**
     * @see org.eclipse.jetty.server.session.SessionCache#setSessionDataStore(org.eclipse.jetty.server.session.SessionDataStore)
     */
    @Override
    public void setSessionDataStore(SessionDataStore sessionStore)
    {
        updateBean(_sessionDataStore, sessionStore);
        _sessionDataStore = sessionStore;
    }

    /**
     * @see org.eclipse.jetty.server.session.SessionCache#getEvictionPolicy()
     */
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
     * @see org.eclipse.jetty.server.session.SessionCache#setEvictionPolicy(int)
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
     * @see org.eclipse.jetty.server.session.SessionCache#get(java.lang.String)
     */
    @Override
    public Session get(String id) throws Exception
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
     * @return the session if it exists, null otherwise
     * @throws Exception if the session cannot be loaded
     */
    protected Session getAndEnter(String id, boolean enter) throws Exception
    {
        Session session = null;
        AtomicReference<Exception> exception = new AtomicReference<Exception>();

        session = doComputeIfAbsent(id, k ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Session {} not found locally in {}, attempting to load", id, this);

            try
            {
                Session s = loadSession(k);
                if (s != null)
                {
                    try (Lock lock = s.lock())
                    {
                        s.setResident(true); //ensure freshly loaded session is resident
                    }
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} not loaded by store", id);
                }
                return s;
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
            
        if (session != null)
        {
            try (Lock lock = session.lock())
            {
                if (!session.isResident()) //session isn't marked as resident in cache
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Non-resident session {} in cache", id);
                    return null;
                }
                else if (enter)
                {
                    session.use();
                }
            }
        }

        return session;
    }

    /**
     * Load the info for the session from the session data store
     *
     * @param id the id
     * @return a Session object filled with data or null if the session doesn't exist
     */
    private Session loadSession(String id)
        throws Exception
    {
        SessionData data = null;
        Session session = null;

        if (_sessionDataStore == null)
            return null; //can't load it

        try
        {
            data = _sessionDataStore.load(id);

            if (data == null) //session doesn't exist
                return null;

            data.setLastNode(_context.getWorkerName());//we are going to manage the node
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
     * @param session
     */
    @Override
    public void add(String id, Session session) throws Exception
    {
        if (id == null || session == null)
            throw new IllegalArgumentException("Add key=" + id + " session=" + (session == null ? "null" : session.getId()));

        try (Lock lock = session.lock())
        {
            if (session.getSessionHandler() == null)
                throw new IllegalStateException("Session " + id + " is not managed");

            if (!session.isValid())
                throw new IllegalStateException("Session " + id + " is not valid");

            if (doPutIfAbsent(id, session) == null)
            {
                session.setResident(true); //its in the cache
                session.use(); //the request is using it
            }
            else
                throw new IllegalStateException("Session " + id + " already in cache");
        }
    }

    /**
     * A response that has accessed this session is about to
     * be returned to the client. Pass the session to the store
     * to persist, so that any changes will be visible to
     * subsequent requests on the same node (if using NullSessionCache),
     * or on other nodes.
     */
    @Override
    public void commit(Session session) throws Exception
    {
        if (session == null)
            return;

        try (Lock lock = session.lock())
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
                    session.willPassivate();
                    _sessionDataStore.store(session.getId(), session.getSessionData());
                    session.didActivate();
                }
            }
        }
    }

    /**
     * @deprecated use {@link #release(String, Session)} instead
     */
    @Override
    @Deprecated
    public void put(String id, Session session) throws Exception
    {
        release(id, session);
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
     * @see org.eclipse.jetty.server.session.SessionCache#release(java.lang.String, org.eclipse.jetty.server.session.Session)
     */
    @Override
    public void release(String id, Session session) throws Exception
    {
        if (id == null || session == null)
            throw new IllegalArgumentException("Put key=" + id + " session=" + (session == null ? "null" : session.getId()));

        try (Lock lock = session.lock())
        {
            if (session.getSessionHandler() == null)
                throw new IllegalStateException("Session " + id + " is not managed");

            if (session.isInvalid())
                return;

            session.complete();

            //don't do anything with the session until the last request for it has finished
            if ((session.getRequests() <= 0))
            {
                //save the session
                if (!_sessionDataStore.isPassivating())
                {
                    //if our backing datastore isn't the passivating kind, just save the session
                    _sessionDataStore.store(id, session.getSessionData());
                    //if we evict on session exit, boot it from the cache
                    if (getEvictionPolicy() == EVICT_ON_SESSION_EXIT)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Eviction on request exit id={}", id);
                        doDelete(session.getId());
                        session.setResident(false);
                    }
                    else
                    {
                        session.setResident(true);
                        doPutIfAbsent(id, session); //ensure it is in our map
                        if (LOG.isDebugEnabled())
                            LOG.debug("Non passivating SessionDataStore, session in SessionCache only id={}", id);
                    }
                }
                else
                {
                    //backing store supports passivation, call the listeners
                    session.willPassivate();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session passivating id={}", id);
                    _sessionDataStore.store(id, session.getSessionData());

                    if (getEvictionPolicy() == EVICT_ON_SESSION_EXIT)
                    {
                        //throw out the passivated session object from the map
                        doDelete(id);
                        session.setResident(false);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Evicted on request exit id={}", id);
                    }
                    else
                    {
                        //reactivate the session
                        session.didActivate();
                        session.setResident(true);
                        doPutIfAbsent(id, session);//ensure it is in our map
                        if (LOG.isDebugEnabled())
                            LOG.debug("Session reactivated id={}", id);
                    }
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Req count={} for id={}", session.getRequests(), id);
                session.setResident(true);
                doPutIfAbsent(id, session); //ensure it is the map, but don't save it to the backing store until the last request exists
            }
        }
    }

    /**
     * Check to see if a session corresponding to the id exists.
     *
     * This method will first check with the object store. If it
     * doesn't exist in the object store (might be passivated etc),
     * it will check with the data store.
     *
     * @throws Exception the Exception
     * @see org.eclipse.jetty.server.session.SessionCache#exists(java.lang.String)
     */
    @Override
    public boolean exists(String id) throws Exception
    {
        //try the object store first
        Session s = doGet(id);
        if (s != null)
        {
            try (Lock lock = s.lock())
            {
                //wait for the lock and check the validity of the session
                return s.isValid();
            }
        }

        //not there, so find out if session data exists for it
        return _sessionDataStore.exists(id);
    }

    /**
     * Check to see if this cache contains an entry for the session
     * corresponding to the session id.
     *
     * @see org.eclipse.jetty.server.session.SessionCache#contains(java.lang.String)
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
     * @see org.eclipse.jetty.server.session.SessionCache#delete(java.lang.String)
     */
    @Override
    public Session delete(String id) throws Exception
    {
        //get the session, if its not in memory, this will load it
        Session session = getAndEnter(id, false);

        //Always delete it from the backing data store
        if (_sessionDataStore != null)
        {
            boolean dsdel = _sessionDataStore.delete(id);
            if (LOG.isDebugEnabled())
                LOG.debug("Session {} deleted in session data store {}", id, dsdel);
        }

        //delete it from the session object store
        if (session != null)
        {
            session.setResident(false);
        }

        return doDelete(id);
    }

    /**
     * @see org.eclipse.jetty.server.session.SessionCache#checkExpiration(Set)
     */
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
                Session s = doGet(c);
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
    public void checkInactiveSession(Session session)
    {
        if (session == null)
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("Checking for idle {}", session.getId());
        try (Lock s = session.lock())
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
                            session.willPassivate();

                        //Fake being dirty to force the write
                        session.getSessionData().setDirty(true);
                        _sessionDataStore.store(session.getId(), session.getSessionData());
                    }

                    doDelete(session.getId()); //detach from this cache
                    session.setResident(false);
                }
                catch (Exception e)
                {
                    LOG.warn("Passivation of idle session {} failed", session.getId());
                    LOG.warn(e);
                }
            }
        }
    }

    @Override
    public Session renewSessionId(String oldId, String newId, String oldExtendedId, String newExtendedId)
        throws Exception
    {
        if (StringUtil.isBlank(oldId))
            throw new IllegalArgumentException("Old session id is null");
        if (StringUtil.isBlank(newId))
            throw new IllegalArgumentException("New session id is null");

        Session session = getAndEnter(oldId, true);
        renewSessionId(session, newId, newExtendedId);

        return session;
    }

    /**
     * Swap the id on a session.
     *
     * @param session the session for which to do the swap
     * @param newId the new id
     * @param newExtendedId the full id plus node id
     * @throws Exception if there was a failure saving the change
     */
    protected void renewSessionId(Session session, String newId, String newExtendedId)
        throws Exception
    {
        if (session == null)
            return;

        try (Lock lock = session.lock())
        {
            String oldId = session.getId();
            session.checkValidForWrite(); //can't change id on invalid session
            session.getSessionData().setId(newId);
            session.getSessionData().setLastSaved(0); //pretend that the session has never been saved before to get a full save
            session.getSessionData().setDirty(true);  //ensure we will try to write the session out    
            session.setExtendedId(newExtendedId); //remember the new extended id
            session.setIdChanged(true); //session id changed

            doPutIfAbsent(newId, session); //put the new id into our map
            doDelete(oldId); //take old out of map

            if (_sessionDataStore != null)
            {
                _sessionDataStore.delete(oldId);  //delete the session data with the old id
                _sessionDataStore.store(newId, session.getSessionData()); //save the session data with the new id
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Session id {} swapped for new id {}", oldId, newId);
        }
    }

    /**
     * @see org.eclipse.jetty.server.session.SessionCache#setSaveOnInactiveEviction(boolean)
     */
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

    /**
     * @see org.eclipse.jetty.server.session.SessionCache#newSession(javax.servlet.http.HttpServletRequest, java.lang.String, long, long)
     */
    @Override
    public Session newSession(HttpServletRequest request, String id, long time, long maxInactiveMs)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Creating new session id=" + id);
        Session session = newSession(request, _sessionDataStore.newSessionData(id, time, time, time, maxInactiveMs));
        session.getSessionData().setLastNode(_context.getWorkerName());
        try
        {
            if (isSaveOnCreate() && _sessionDataStore != null)
                _sessionDataStore.store(id, session.getSessionData());
        }
        catch (Exception e)
        {
            LOG.warn("Save of new session {} failed", id);
            LOG.warn(e);
        }
        return session;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[evict=%d,removeUnloadable=%b,saveOnCreate=%b,saveOnInactiveEvict=%b]",
            this.getClass().getName(), this.hashCode(), _evictionPolicy,
            _removeUnloadableSessions, _saveOnCreate, _saveOnInactiveEviction);
    }
}
