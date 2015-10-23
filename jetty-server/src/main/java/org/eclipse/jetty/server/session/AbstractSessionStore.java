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


package org.eclipse.jetty.server.session;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.util.component.AbstractLifeCycle;

/**
 * AbstractSessionStore
 *
 *
 */
public abstract class AbstractSessionStore extends AbstractLifeCycle implements SessionStore
{
    protected SessionDataStore _sessionDataStore;
    protected StalenessStrategy _staleStrategy;
    protected SessionManager _manager;
    

    

    public abstract Session newSession (SessionData data);

    public abstract Session doGet(SessionKey key);
    
    public abstract void doPut (SessionKey key, Session session);
    
    public abstract boolean doExists (SessionKey key);
    
    public abstract void doDelete (SessionKey key);
    
    public abstract Set<SessionKey> doGetExpiredCandidates();
    
    
    public AbstractSessionStore ()
    {
    }
    
    
    public void setSessionManager (SessionManager manager)
    {
        _manager = manager;
    }
    
    public SessionManager getSessionManager()
    {
        return _manager;
    }
    
    
    @Override
    protected void doStart() throws Exception
    {
        if (_sessionDataStore == null)
            throw new IllegalStateException ("No session data store configured");
        
        if (_manager == null)
            throw new IllegalStateException ("No session manager");


        if (_sessionDataStore instanceof AbstractSessionDataStore)
        {
            ((AbstractSessionDataStore)_sessionDataStore).setContext(_manager.getContext());
            ((AbstractSessionDataStore)_sessionDataStore).setNode(_manager.getSessionIdManager().getWorkerName());
        }
        
        _sessionDataStore.start();
        
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        _sessionDataStore.stop();
        super.doStop();
    }

    public SessionDataStore getSessionDataStore()
    {
        return _sessionDataStore;
    }

    public void setSessionDataStore(SessionDataStore sessionDataStore)
    {
        _sessionDataStore = sessionDataStore;
    }
    
    public StalenessStrategy getStaleStrategy()
    {
        return _staleStrategy;
    }

    public void setStaleStrategy(StalenessStrategy staleStrategy)
    {
        _staleStrategy = staleStrategy;
    }

    /** 
     * Get a session object.
     * 
     * If the session object is not in this session store, try getting
     * the data for it from a SessionDataStore associated with the 
     * session manager.
     * 
     * @see org.eclipse.jetty.server.session.SessionStore#get(java.lang.String)
     */
    @Override
    public Session get(SessionKey key) throws Exception
    {
        //look locally
        Session session = doGet(key);

        //not in session store, load the data for the session if possible
        if (session == null && _sessionDataStore != null)
        {
            SessionData data = _sessionDataStore.load(key);
            if (data != null)
            {
                session = newSession(data);
                session.setSessionManager(_manager);
                doPut(key, session);
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
    public void put(SessionKey key, Session session) throws Exception
    {
        if (key == null || session == null)
            throw new IllegalArgumentException ("Put key="+key+" session="+(session==null?"null":session.getId()));
        
        session.setSessionManager(_manager);
        
        //if the session is already in our cache, then we want to write through any changes
        if (doExists(key))
        {
            //if the session data has changed, or the cache is considered stale, write it to any backing store
            if ((session.getSessionData().isDirty() || isStale(session)) && _sessionDataStore != null)
            {
                session.willPassivate();
                _sessionDataStore.store(key, session.getSessionData());
                session.didActivate();
            }
        }
        else
        {
            //session not already in cache, add it and write through
            if (_sessionDataStore != null)
            {
                session.willPassivate();
                _sessionDataStore.store(SessionKey.getKey(session.getSessionData()), session.getSessionData());
                session.didActivate();
            }
            doPut(key,session);
        }

    }

    /** 
     * Check to see if the session object exists.
     * 
     * TODO should this check through to the backing store?
     * 
     * @see org.eclipse.jetty.server.session.SessionStore#exists(java.lang.String)
     */
    @Override
    public boolean exists(SessionKey key)
    {
        return doExists(key);
    }


    /** 
     * Remove a session object from this store and from any backing store.
     * 
     * @see org.eclipse.jetty.server.session.SessionStore#delete(java.lang.String)
     */
    @Override
    public boolean delete(SessionKey key) throws Exception
    {
        boolean deleted =  true;
        if (_sessionDataStore != null)
            deleted = _sessionDataStore.delete(key);
        doDelete(key);
        return deleted;
    }

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
    public Set<SessionKey> getExpired()
    {
       if (!isStarted())
           return Collections.emptySet();
       Set<SessionKey> candidates = doGetExpiredCandidates();
       return _sessionDataStore.getExpired(candidates);
    }

}
