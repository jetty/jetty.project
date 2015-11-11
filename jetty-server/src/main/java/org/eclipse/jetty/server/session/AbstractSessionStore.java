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
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.statistic.CounterStatistic;

/**
 * AbstractSessionStore
 *
 *
 */
public abstract class AbstractSessionStore extends AbstractLifeCycle implements SessionStore
{
    final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    protected SessionDataStore _sessionDataStore;
    protected StalenessStrategy _staleStrategy;
    protected SessionManager _manager;
    protected final CounterStatistic _sessionStats = new CounterStatistic();

    

    /**
     * Create a new Session object from session data
     * @param data
     * @return
     */
    public abstract Session newSession (SessionData data);

    
    
    /**
     * Get the session matching the key
     * @param key
     * @return
     */
    public abstract Session doGet(SessionKey key);
    
    
    
    /**
     * Put the session into the map if it wasn't already there
     * 
     * @param key the identity of the session
     * @param session the session object
     * @return null if the session wasn't already in the map, or the existing entry otherwise
     */
    public abstract Session doPutIfAbsent (SessionKey key, Session session);
    
    
    
    /**
     * Check to see if the session exists in the store
     * @param key
     * @return
     */
    public abstract boolean doExists (SessionKey key);
    
    
    
    /**
     * Remove the session with this identity from the store
     * @param key
     * @return the removed Session or null if no such key
     */
    public abstract Session doDelete (SessionKey key);
    
    
    
    
    /**
     * Get a list of keys for sessions that the store thinks has expired
     * @return
     */
    public abstract Set<SessionKey> doGetExpiredCandidates();
    
    
    
    
    /**
     * 
     */
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
    public Session get(SessionKey key, boolean staleCheck) throws Exception
    {
        //look locally
        Session session = doGet(key);
        
        
        if (staleCheck && isStale(session))
        {
            //delete from memory so should reload
            doDelete(key);
            session = null;
            _sessionStats.decrement();
        }

        
        //not in session store, load the data for the session if possible
        if (session == null && _sessionDataStore != null)
        {
            SessionData data = _sessionDataStore.load(key);
            if (data != null)
            {
                session = newSession(data);
                session.setSessionManager(_manager);
                Session existing = doPutIfAbsent(key, session);
                if (existing != null)
                {
                    //some other thread has got in first and added the session
                    //so use it
                    session = existing;
                }
                else
                    _sessionStats.increment();
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

        Session existing = doPutIfAbsent(key,session);
        if (existing == null)
        {
            //session not already in cache write through
            if (_sessionDataStore != null)
            {
                session.willPassivate();
                _sessionDataStore.store(SessionKey.getKey(session.getSessionData()), session.getSessionData());
                session.didActivate();
            }
            _sessionStats.increment();
        }
        else
        {
            //if the session data has changed, or the cache is considered stale, write it to any backing store
            if ((session.getSessionData().isDirty() || isStale(session)) && _sessionDataStore != null)
            {
                session.willPassivate();
                _sessionDataStore.store(key, session.getSessionData());
                session.didActivate();
            }
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
        if (_sessionDataStore != null)
        {
            boolean dsdel = _sessionDataStore.delete(key);
            if (LOG.isDebugEnabled()) LOG.debug("Session {} deleted in db {}",key, dsdel);                   
        }
        if (doDelete(key) != null)
        {
            _sessionStats.decrement();
            return true;
        }
        return false;
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





    @Override
    public Session newSession(HttpServletRequest request, SessionKey key, long time, long maxInactiveMs)
    {
        return null;
    }



    @Override
    public int getSessions()
    {
       return (int)_sessionStats.getCurrent();
    }



    @Override
    public int getSessionsMax()
    {
        return (int)_sessionStats.getMax();
    }



    @Override
    public int getSessionsTotal()
    {
       return (int)_sessionStats.getTotal();
    }



    @Override
    public void resetStats()
    {
        _sessionStats.reset();
    }

}
