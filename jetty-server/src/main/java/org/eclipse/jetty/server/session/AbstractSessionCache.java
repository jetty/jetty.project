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

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.DumpableCollection;
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
 * 
 */
public abstract class AbstractSessionCache extends ContainerLifeCycle implements SessionCache
{
    final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
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
     * Create a new Session object from pre-existing session data
     * @param data the session data
     * @return a new Session object
     */
    public abstract Session newSession (SessionData data);
    
    
    /**
     * Create a new Session for a request.
     * 
     * @param request the request
     * @param data the session data
     * @return the new session
     */
    public abstract Session newSession (HttpServletRequest request, SessionData data);
    
    
    /**
     * Get the session matching the key
     * @param id session id
     * @return the Session object matching the id
     */
    public abstract Session doGet(String id);
    
    
    
    /**
     * Put the session into the map if it wasn't already there
     * 
     * @param id the identity of the session
     * @param session the session object
     * @return null if the session wasn't already in the map, or the existing entry otherwise
     */
    public abstract Session doPutIfAbsent (String id, Session session);
    
    
    /**
     * Replace the mapping from id to oldValue with newValue
     * @param id the id
     * @param oldValue the old value
     * @param newValue the new value
     * @return true if replacement was done
     */
    public abstract boolean doReplace (String id, Session oldValue, Session newValue);
    

    
    /**
     * Remove the session with this identity from the store
     * @param id the id
     * @return true if removed false otherwise
     */
    public abstract Session doDelete (String id);

    
    
    /**
     * PlaceHolder
     */
    protected class PlaceHolderSession extends Session
    {

        /**
         * @param data the session data
         */
        public PlaceHolderSession(SessionData data)
        {
            super(null, data);
        }
    }
    
    
    
    /**
     * 
     */
    public AbstractSessionCache (SessionHandler handler)
    {
        _handler = handler;
    }
    
    
 
    
    /**
     * @return the SessionManger
     */
    public SessionHandler getSessionHandler()
    {
        return _handler;
    }
    

    /** 
     * @see org.eclipse.jetty.server.session.SessionCache#initialize(org.eclipse.jetty.server.session.SessionContext)
     */
    public void initialize (SessionContext context)
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
            throw new IllegalStateException ("No session data store configured");
        
        if (_handler == null)
            throw new IllegalStateException ("No session manager");
        
        if (_context == null)
            throw new IllegalStateException ("No ContextId");

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
    public SessionDataStore getSessionDataStore()
    {
        return _sessionDataStore;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionCache#setSessionDataStore(org.eclipse.jetty.server.session.SessionDataStore)
     */
    public void setSessionDataStore(SessionDataStore sessionStore)
    {
        updateBean(_sessionDataStore, sessionStore);
        _sessionDataStore = sessionStore;
    }
    





    /** 
     * @see org.eclipse.jetty.server.session.SessionCache#getEvictionPolicy()
     */
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
    public void setEvictionPolicy(int evictionTimeout)
    {
        _evictionPolicy = evictionTimeout;
    }


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
    @Override
    public boolean isRemoveUnloadableSessions()
    {
        return _removeUnloadableSessions;
    }


    /**
     * If a session's data cannot be loaded from the store without error, remove
     * it from the persistent store.
     * 
     * @param removeUnloadableSessions 
     */
    @Override
    public void setRemoveUnloadableSessions(boolean removeUnloadableSessions)
    {
        _removeUnloadableSessions = removeUnloadableSessions;
    }


    /** 
     *  Get a session object.
     * 
     * If the session object is not in this session store, try getting
     * the data for it from a SessionDataStore associated with the 
     * session manager.
     * 
     * @see org.eclipse.jetty.server.session.SessionCache#get(java.lang.String)
     */
    @Override
    public Session get(String id) throws Exception
    {
        Session session = null;
        Exception ex = null;
        
        while (true)
        {
            session = doGet(id);
            
            if (_sessionDataStore == null)
                break; //can't load any session data so just return null or the session object
            
            if (session == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Session {} not found locally, attempting to load", id);
       
                //didn't get a session, try and create one and put in a placeholder for it
                PlaceHolderSession phs = new PlaceHolderSession (new SessionData(id, null, null,0,0,0,0));
                Lock phsLock = phs.lock();
                Session s = doPutIfAbsent(id, phs);
                if (s == null)
                {
                    //My placeholder won, go ahead and load the full session data
                    try
                    {
                        session = loadSession(id);
                        if (session == null)
                        {
                            //session does not exist, remove the placeholder
                            doDelete(id);
                            phsLock.close();
                            break;
                        }
                        
                        try (Lock lock = session.lock())
                        {
                            //swap it in instead of the placeholder
                            boolean success = doReplace(id, phs, session);
                            if (!success)
                            {
                                //something has gone wrong, it should have been our placeholder
                                doDelete(id);
                                session = null;
                                LOG.warn("Replacement of placeholder for session {} failed", id);
                                phsLock.close();
                                break;
                            }
                            else
                            {
                                //successfully swapped in the session
                                session.setResident(true);
                                session.updateInactivityTimer();
                                phsLock.close();
                                break;
                            }
                        }
                    }
                    catch (Exception e)
                    {
                        ex = e; //remember a problem happened loading the session
                        doDelete(id); //remove the placeholder
                        phsLock.close();
                        session = null;
                        break;
                    }
                }
                else
                {
                    //my placeholder didn't win, check the session returned
                    phsLock.close();
                    try (Lock lock = s.lock())
                    {
                        //is it a placeholder? or is a non-resident session? In both cases, chuck it away and start again
                        if (!s.isResident() || s instanceof PlaceHolderSession)
                        {
                            session = null;
                            continue;
                        }
                        session = s;
                        break;
                    }
                }
                
            }
            else
            {
                //check the session returned
                try (Lock lock = session.lock())
                {                    
                    //is it a placeholder? or is it passivated? In both cases, chuck it away and start again
                    if (!session.isResident()|| session instanceof PlaceHolderSession)
                    {
                        session = null;
                        continue;
                    }

                    //got the session
                    break;
                }
            }
        }

        if (ex != null)
            throw ex;
        return session;
    }

    /**
     * Load the info for the session from the session data store
     * 
     * @param id the id
     * @return a Session object filled with data or null if the session doesn't exist
     * @throws Exception
     */
    private Session loadSession (String id)
    throws Exception
    {
        SessionData data = null;
        Session session = null;

        if (_sessionDataStore == null)
            return null; //can't load it

        try
        {
            data =_sessionDataStore.load(id);

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
     * Put the Session object back into the session store. 
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
     * @see org.eclipse.jetty.server.session.SessionCache#put(java.lang.String, org.eclipse.jetty.server.session.Session)
     */
    @Override
    public void put(String id, Session session) throws Exception
    {
        if (id == null || session == null)
            throw new IllegalArgumentException ("Put key="+id+" session="+(session==null?"null":session.getId()));
       
        try (Lock lock = session.lock())
        {   
            if (session.getSessionHandler() == null)
                throw new IllegalStateException("Session "+id+" is not managed");
            
            if (!session.isValid())
                return;
            
            if (_sessionDataStore == null)
            {
                if (LOG.isDebugEnabled()) LOG.debug("No SessionDataStore, putting into SessionCache only id={}", id);
                session.setResident(true);
                if (doPutIfAbsent(id, session) == null) //ensure it is in our map
                    session.updateInactivityTimer();
                return;
            }       

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
                        if (LOG.isDebugEnabled()) LOG.debug("Eviction on request exit id={}", id);
                        doDelete(session.getId());
                        session.setResident(false);
                    }
                    else
                    {
                        session.setResident(true);
                        if (doPutIfAbsent(id,session) == null) //ensure it is in our map 
                            session.updateInactivityTimer();
                        if (LOG.isDebugEnabled())LOG.debug("Non passivating SessionDataStore, session in SessionCache only id={}",id);
                    }
                }
                else
                {
                    //backing store supports passivation, call the listeners
                    session.willPassivate();
                    if (LOG.isDebugEnabled()) LOG.debug("Session passivating id={}", id);
                    _sessionDataStore.store(id, session.getSessionData());
               
                    if (getEvictionPolicy() == EVICT_ON_SESSION_EXIT)
                    {
                        //throw out the passivated session object from the map
                        doDelete(id);
                        session.setResident(false);
                        if (LOG.isDebugEnabled()) LOG.debug("Evicted on request exit id={}", id);
                    }
                    else
                    {
                        //reactivate the session
                        session.didActivate();    
                        session.setResident(true);
                        if (doPutIfAbsent(id,session) == null) //ensure it is in our map  
                            session.updateInactivityTimer();
                        if (LOG.isDebugEnabled())LOG.debug("Session reactivated id={}",id);
                    }
                }
            }
            else
            {
                if (LOG.isDebugEnabled()) LOG.debug("Req count={} for id={}",session.getRequests(),id);
                session.setResident(true);
                if (doPutIfAbsent(id, session) == null) //ensure it is the map, but don't save it to the backing store until the last request exists
                    session.updateInactivityTimer();
            }
        }
    }

    /** 
     * Check to see if a session corresponding to the id exists.
     * 
     * This method will first check with the object store. If it
     * doesn't exist in the object store (might be passivated etc),
     * it will check with the data store.
     * @throws Exception the Exception
     * 
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
        return _sessionDataStore.exists (id);
    }
    
    /** 
     * Check to see if this cache contains an entry for the session
     * corresponding to the session id.
     * 
     * @see org.eclipse.jetty.server.session.SessionCache#contains(java.lang.String)
     */
    @Override
    public boolean contains (String id) throws Exception
    {
        //just ask our object cache, not the store
        return (doGet(id) != null);
    }


    /** 
     * Remove a session object from this store and from any backing store.
     * 
     * 
     * @see org.eclipse.jetty.server.session.SessionCache#delete(java.lang.String)
     */
    @Override
    public Session delete(String id) throws Exception
    {
        //get the session, if its not in memory, this will load it
        Session session = get(id); 

 
        //Always delete it from the backing data store
        if (_sessionDataStore != null)
        {

            boolean dsdel = _sessionDataStore.delete(id);
            if (LOG.isDebugEnabled()) LOG.debug("Session {} deleted in db {}",id, dsdel);                   
        }
        
        //delete it from the session object store
        if (session != null)
        {
            session.stopInactivityTimer();
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
           LOG.debug("SessionDataStore checking expiration on {}", candidates);
       return _sessionDataStore.getExpired(candidates);
    }

    
    
    /**
     * Check a session for being inactive and
     * thus being able to be evicted, if eviction
     * is enabled.
     * 
     * 
     * @param session session to check
     */
    public void checkInactiveSession (Session session)
    {
        if (session == null)
            return;

      if (LOG.isDebugEnabled()) LOG.debug("Checking for idle {}", session.getId());
        try (Lock s = session.lock())
        {
            if (getEvictionPolicy() > 0 && session.isIdleLongerThan(getEvictionPolicy()) && session.isValid() && session.isResident() && session.getRequests() <= 0)
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

                        _sessionDataStore.store(session.getId(), session.getSessionData());
                    }
               
                    doDelete(session.getId()); //detach from this cache
                    session.setResident(false);
                }
                catch (Exception e)
                {
                    LOG.warn("Passivation of idle session {} failed", session.getId(), e);
                    session.updateInactivityTimer();
                }
            }
        }
    }
    
 

    


    /** 
     * @see org.eclipse.jetty.server.session.SessionCache#renewSessionId(java.lang.String, java.lang.String)
     */
    public Session renewSessionId (String oldId, String newId)
    throws Exception
    {
        if (StringUtil.isBlank(oldId))
            throw new IllegalArgumentException ("Old session id is null");
        if (StringUtil.isBlank(newId))
            throw new IllegalArgumentException ("New session id is null");

        Session session = get(oldId);
        if (session == null)
            return null;

        try (Lock lock = session.lock())
        {
            session.checkValidForWrite(); //can't change id on invalid session
            session.getSessionData().setId(newId);
            session.getSessionData().setLastSaved(0); //pretend that the session has never been saved before to get a full save
            session.getSessionData().setDirty(true);  //ensure we will try to write the session out
            doPutIfAbsent(newId, session); //put the new id into our map
            doDelete (oldId); //take old out of map
            if (_sessionDataStore != null)
            {
                _sessionDataStore.delete(oldId);  //delete the session data with the old id
                _sessionDataStore.store(newId, session.getSessionData()); //save the session data with the new id
            }
            LOG.info("Session id {} swapped for new id {}", oldId, newId);
            return session;
        }
    }
    
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionCache#setSaveOnInactiveEviction(boolean)
     */
    @Override
    public void setSaveOnInactiveEviction (boolean saveOnEvict)
    {
        _saveOnInactiveEviction = saveOnEvict;
    }
    
    
    /**
     * Whether we should save a session that has been inactive before
     * we boot it from the cache.
     * 
     * @return true if an inactive session will be saved before being evicted
     */
    @Override
    public boolean isSaveOnInactiveEviction ()
    {
        return _saveOnInactiveEviction;
    }


    /** 
     * @see org.eclipse.jetty.server.session.SessionCache#newSession(javax.servlet.http.HttpServletRequest, java.lang.String, long, long)
     */
    @Override
    public Session newSession(HttpServletRequest request, String id, long time, long maxInactiveMs)
    {
        if (LOG.isDebugEnabled()) LOG.debug("Creating new session id="+id);
        Session session = newSession(request, _sessionDataStore.newSessionData(id, time, time, time, maxInactiveMs));
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
                             this.getClass().getName(),this.hashCode(),_evictionPolicy,_removeUnloadableSessions,_saveOnCreate,_saveOnInactiveEviction);
    }
}
