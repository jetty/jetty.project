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


package org.eclipse.jetty.server.session.x;

/**
 * AbstractSessionStore
 *
 *
 */
public abstract class AbstractSessionStore implements SessionStore
{
    protected SessionDataStore _sessionDataStore;

    

    public abstract Session newSession (SessionData data);

    public abstract Session doGet(String id);
    
    public abstract Session doPutIfAbsent (String id, Session session);
    
    public abstract void doPut (String id, Session session);
    
    public abstract boolean doExists (String id);
    
    public abstract void doDelete (String id);
    
   
    public AbstractSessionStore ()
    {
 
    }
    
    
    public SessionDataStore getSessionDataStore()
    {
        return _sessionDataStore;
    }

    public void setSessionDataStore(SessionDataStore sessionDataStore)
    {
        _sessionDataStore = sessionDataStore;
    }
    
    /** 
     * Get a session object.
     * 
     * If the session object is not in this session store, try getting
     * the data for it from a SessionDataStore associated with the 
     * session manager.
     * 
     * @see org.eclipse.jetty.server.session.x.SessionStore#get(java.lang.String)
     */
    @Override
    public Session get(String id) throws Exception
    {
        //look locally
        Session session = doGet(id);
        
        //not in session store, load the data for the session if possible
        if (session == null && _sessionDataStore != null)
        {
            SessionData data = _sessionDataStore.load(id);
            session = newSession(data);
            doPut(id, session);
        }
        return session;
    }

    /** 
     * Put the Session object into the session store. 
     * If the session manager supports a session data store, write the
     * session data through to the session data store.
     * 
     * @see org.eclipse.jetty.server.session.x.SessionStore#put(java.lang.String, org.eclipse.jetty.server.session.x.Session)
     */
    @Override
    public void put(String id, Session session) throws Exception
    {
        //if the session is already in our cache, then we want to write through any changes
        if (doExists(id))
        {
            //if the session data has changed, or the cache is considered stale, write it to any backing store
            if ((session.getSessionData().isDirty() || isStale(session)) && _sessionDataStore != null)
            {
                session.willPassivate();
                _sessionDataStore.store(id, session.getSessionData());
                session.didActivate();
            }
        }
        else
        {
            //session not already in cache, add it and write through
            if (_sessionDataStore != null)
            {
                session.willPassivate();
                _sessionDataStore.store(id, session.getSessionData());
                session.didActivate();
            }
            doPut(id,session);
        }

    }

    /** 
     * Check to see if the session object exists.
     * 
     * TODO should this check through to the backing store?
     * 
     * @see org.eclipse.jetty.server.session.x.SessionStore#exists(java.lang.String)
     */
    @Override
    public boolean exists(String id)
    {
        return doExists(id);
    }

    /** 
     *TODO does this mean absent in the Session cache or the backing store???
     *
     * @see org.eclipse.jetty.server.session.x.SessionStore#putIfAbsent(java.lang.String, org.eclipse.jetty.server.session.x.Session)
     */
    @Override
    public Session putIfAbsent(String id, Session session) throws Exception
    {
        return doPutIfAbsent(id, session);
    }

    /** 
     * Remove a session object from this store and from any backing store.
     * 
     * @see org.eclipse.jetty.server.session.x.SessionStore#delete(java.lang.String)
     */
    @Override
    public boolean delete(String id) throws Exception
    {
        boolean deleted =  true;
        //TODO synchronization???
        if (_sessionDataStore != null)
            deleted = _sessionDataStore.delete(id);
        doDelete(id);
        return deleted;
    }

    public boolean isStale (Session session)
    {
        //TODO implement (pluggable?) algorithm for deciding if memory is stale
        return false;
    }
}
