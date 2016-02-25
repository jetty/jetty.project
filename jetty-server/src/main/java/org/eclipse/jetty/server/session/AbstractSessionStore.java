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
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

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
     * Get a list of keys for sessions that the store thinks has expired
     * @return ids of all Session objects that might have expired
     */
    public abstract Set<String> doGetExpiredCandidates();
    
    
    
    
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
        
        
        if (staleCheck && isStale(session))
        {
            //delete from session store so should reload from session data store
            doDelete(id);
            session = null;
        }
        
        //not in session store, load the data for the session if possible
        if (session == null && _sessionDataStore != null)
        {
            SessionData data = _sessionDataStore.load(id);
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
     * @see org.eclipse.jetty.server.session.SessionStore#delete(java.lang.String)
     */
    @Override
    public Session delete(String id) throws Exception
    {
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
     * @see org.eclipse.jetty.server.session.SessionStore#getExpired()
     */
    @Override
    public Set<String> getExpired()
    {
       if (!isStarted())
           return Collections.emptySet();
       Set<String> candidates = doGetExpiredCandidates();
       return _sessionDataStore.getExpired(candidates);
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
