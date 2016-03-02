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

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Locker.Lock;

/**
 * AbstractSessionStore
 *
 * Basic behaviour for maintaining an in-memory store of Session objects and 
 * making sure that any backing SessionDataStore is kept in sync.
 */
public abstract class AbstractSessionStore extends AbstractLifeCycle implements SessionStore
{
    final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    protected SessionDataStore _sessionDataStore;
    protected StalenessStrategy _staleStrategy;
    protected SessionManager _manager;
    protected SessionContext _context;
    protected int _idlePassivationTimeoutSec;
    private IdleInspector _idleInspector;
    private ExpiryInspector _expiryInspector;
    

    /**
     * Create a new Session object from session data
     * @param data
     * @return a new Session object
     */
    public abstract Session newSession (SessionData data);

    
    
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
     * Check to see if the session exists in the store
     * @param id
     * @return true if the Session object exists in the session store
     */
    public abstract boolean doExists (String id);
    
    
    
    /**
     * Remove the session with this identity from the store
     * @param id
     * @return true if removed false otherwise
     */
    public abstract Session doDelete (String id);

    
    
    
    /**
     * 
     */
    public AbstractSessionStore ()
    {
    }
    
    
    /**
     * @param manager
     */
    public void setSessionManager (SessionManager manager)
    {
        _manager = manager;
    }
    
    /**
     * @return the SessionManger
     */
    public SessionManager getSessionManager()
    {
        return _manager;
    }
    

    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#initialize(org.eclipse.jetty.server.session.SessionContext)
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
        
        if (_manager == null)
            throw new IllegalStateException ("No session manager");
        
        if (_context == null)
            throw new IllegalStateException ("No ContextId");
        
        _sessionDataStore.initialize(_context);
        _sessionDataStore.start();
        
        _expiryInspector = new ExpiryInspector(this, _manager.getSessionIdManager());
        
        super.doStart();
    }

    /** 
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        _sessionDataStore.stop();
        _expiryInspector = null;
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
     * @param sessionDataStore
     */
    public void setSessionDataStore(SessionDataStore sessionDataStore)
    {
        _sessionDataStore = sessionDataStore;
    }
    
    /**
     * @return the strategy for detecting stale sessions or null if there isn't one
     */
    public StalenessStrategy getStaleStrategy()
    {
        return _staleStrategy;
    }

    /**
     * @param staleStrategy
     */
    public void setStaleStrategy(StalenessStrategy staleStrategy)
    {
        _staleStrategy = staleStrategy;
    }


    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#getIdlePassivationTimeoutSec()
     */
    public int getIdlePassivationTimeoutSec()
    {
        return _idlePassivationTimeoutSec;
    }



    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#setIdlePassivationTimeoutSec(int)
     */
    public void setIdlePassivationTimeoutSec(int idleTimeoutSec)
    {
        _idlePassivationTimeoutSec = idleTimeoutSec;
        if (_idlePassivationTimeoutSec == 0)
            _idleInspector = null;
        else if (_idleInspector == null)
            _idleInspector = new IdleInspector(this);
    }



    /** 
     *  Get a session object.
     * 
     * If the session object is not in this session store, try getting
     * the data for it from a SessionDataStore associated with the 
     * session manager.
     * 
     * @see org.eclipse.jetty.server.session.SessionStore#get(java.lang.String, boolean)
     */
    @Override
    public Session get(String id, boolean staleCheck) throws Exception
    {
        //look locally
        Session session = doGet(id);
        
        //TODO also check that session is only written out if only the access time changes infrequently    

        //session is either not in session store, or it is stale, or its been passivated, load the data for the session if possible
        if (session == null || (staleCheck && isStale(session)) || session.isPassivated() && _sessionDataStore != null)
        {
            SessionData data = _sessionDataStore.load(id);
  
            //session wasn't in session store
            if (session == null)
            {
                if (data != null)
                {
                    session = newSession(data);
                    session.setSessionManager(_manager);
                    Session existing = doPutIfAbsent(id, session);
                    if (existing != null)
                    {
                        //some other thread has got in first and added the session
                        //so use it
                        session = existing;
                    }
                }
                //else session not in store and not in data store either, so doesn't exist
            }
            else
            {
                //session was already in session store, refresh it if its still stale/passivated
                try (Lock lock = session.lock())
                {   
                    if (session.isPassivated() || staleCheck && isStale(session))
                    {
                        //if we were able to load it, then update our session object
                        if (data != null)
                        {
                            session.setPassivated(false);
                            session.getSessionData().copy(data);
                            session.didActivate();
                        }
                        else
                            session = null; //TODO rely on the expiry mechanism to get rid of it?
                    }
                }
            }
        }
        return session;
    }

    /** 
     * Put the Session object into the session store. 
     * If the session manager supports a session data store, write the
     * session data through to the session data store.
     * 
     * @see org.eclipse.jetty.server.session.SessionStore#put(java.lang.String, org.eclipse.jetty.server.session.Session)
     */
    @Override
    public void put(String id, Session session) throws Exception
    {
        if (id == null || session == null)
            throw new IllegalArgumentException ("Put key="+id+" session="+(session==null?"null":session.getId()));
        
        session.setSessionManager(_manager);

        //if the session is new, the data has changed, or the cache is considered stale, write it to any backing store
        try (Lock lock = session.lock())
        {
            if ((session.isNew() || session.getSessionData().isDirty() || isStale(session)) && _sessionDataStore != null)
            {
                if (_sessionDataStore.isPassivating())
                {
                    session.willPassivate();
                    try
                    {
                        _sessionDataStore.store(id, session.getSessionData());
                    }
                    finally
                    {
                        session.didActivate();
                    }
                }
                else
                    _sessionDataStore.store(id, session.getSessionData());
            }

        }

        doPutIfAbsent(id,session);
    }

    /** 
     * Check to see if the session object exists in this store.
     * 
     * @see org.eclipse.jetty.server.session.SessionStore#exists(java.lang.String)
     */
    @Override
    public boolean exists(String id)
    {
        return doExists(id);
    }


    /** 
     * Remove a session object from this store and from any backing store.
     * 
     * If session has been passivated, may need to reload it before it can
     * be properly deleted
     * 
     * @see org.eclipse.jetty.server.session.SessionStore#delete(java.lang.String)
     */
    @Override
    public Session delete(String id) throws Exception
    {
        //Ensure that the session object is not passivated so that its attributes
        //are valid
        Session session = doGet(id);
        
        //TODO if (session == null) do we want to load it to delete it?
        if (session != null)
        {
            try (Lock lock = session.lock())
            {
                //TODO don't check stale on deletion?
                if (session.isPassivated() && _sessionDataStore != null)
                {
                    session.setPassivated(false);
                    SessionData data = _sessionDataStore.load(id);
                    if (data != null)
                    {
                        session.getSessionData().copy(data);
                        session.didActivate();
                    }
                }
            }
        }
            
        
        //Always delete it from the data store
        if (_sessionDataStore != null)
        {
            boolean dsdel = _sessionDataStore.delete(id);
            if (LOG.isDebugEnabled()) LOG.debug("Session {} deleted in db {}",id, dsdel);                   
        }
        return doDelete(id);
    }

    
    
    /**
     * @param session
     * @return true or false according to the StaleStrategy
     */
    public boolean isStale (Session session)
    {
        if (_staleStrategy != null)
            return _staleStrategy.isStale(session);
        return false;
    }
    



    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#checkExpiry(java.util.Set)
     */
    @Override
    public Set<String> checkExpiration(Set<String> candidates)
    {
       if (!isStarted())
           return Collections.emptySet();
       
       if (LOG.isDebugEnabled())
           LOG.debug("SessionStore checking expiration on {}", candidates);
       return _sessionDataStore.getExpired(candidates);
    }

    
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#inspect()
     */
    public void inspect ()
    {      
        Stream<Session> stream = getStream();
        try
        {
            _expiryInspector.preInspection();
            if (_idleInspector != null)
                _idleInspector.preInspection();
            stream.forEach(s->{_expiryInspector.inspect(s); if (_idleInspector != null) _idleInspector.inspect(s);});
            _expiryInspector.postInspection();
            _idleInspector.postInspection();
        }
        finally 
        {
            stream.close();
        }
    }
    
   
    

    
    /**
     * If the SessionDataStore supports passivation, passivate any
     * sessions that have not be accessed for longer than x sec
     * 
     * @param id identity of session to passivate
     */
    public void passivateIdleSession(String id)
    {
        if (!isStarted())
            return;
        
        if (_sessionDataStore == null)
            return; //no data store to passivate
        
        if (!_sessionDataStore.isPassivating()) 
            return; //doesn't support passivation
 

        //get the session locally
        Session s = doGet(id);
        
        if (s == null)
        {
            LOG.warn("Session {} not in this session store", s);
            return;
        }


        try (Lock lock = s.lock())
        {
            //check the session is still idle first
            if (s.isValid() && s.isIdleLongerThan(_idlePassivationTimeoutSec))
            {
                s.willPassivate();
                _sessionDataStore.store(id, s.getSessionData());
                s.getSessionData().clearAllAttributes();
                s.getSessionData().setDirty(false);
            }
        }
        catch (Exception e)
        {
            LOG.warn("Passivation of idle session {} failed", id, e);
            //  TODO should do session.invalidate(); ???
        }
    }



    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#newSession(javax.servlet.http.HttpServletRequest, java.lang.String, long, long)
     */
    @Override
    public Session newSession(HttpServletRequest request, String id, long time, long maxInactiveMs)
    {
        return null;
    }
}
