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
     * Replace the mapping from id to oldValue with newValue
     * @param id
     * @param oldValue
     * @param newValue
     * @return true if replacement was done
     */
    public abstract boolean doReplace (String id, Session oldValue, Session newValue);
    
    
    
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
     * PlaceHolder
     *
     *
     */
    protected class PlaceHolderSession extends Session
    {

        /**
         * @param data
         */
        public PlaceHolderSession(SessionData data)
        {
            super(data);
        }
    }
    
    
    
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
                    LOG.debug("Session not found locally, attempting to load");
                
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

                            //successfully swapped in the session
                            phsLock.close();
                            break;
                        }
                    }
                    catch (Exception e)
                    {
                        ex = e; //remember a problem happened loading the session
                        LOG.warn(e);
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
                        //is it a placeholder? or is it passivated? In both cases, chuck it away and start again
                        if (s.isPassivated() || s instanceof PlaceHolderSession)
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
                    if (session.isPassivated() || session instanceof PlaceHolderSession)
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
     * @param id
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
        
        data =_sessionDataStore.load(id);

        if (data == null) //session doesn't exist
            return null;

        session = newSession(data);
        session.setSessionManager(_manager);
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
       


        //if the session is new, the data has changed, or the cache is considered stale, write it to any backing store
        try (Lock lock = session.lock())
        {
            session.setSessionManager(_manager);
            
            if (session.isPassivated())
                throw new IllegalStateException ("Session "+id+" is passivated and cannot be saved");
            
            if (!session.isValid())
                return;
            
            if ((session.isNew() || session.getSessionData().isDirty()) && _sessionDataStore != null)
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
            doPutIfAbsent(id,session);
        }
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
     * 
     * @see org.eclipse.jetty.server.session.SessionStore#delete(java.lang.String)
     */
    @Override
    public Session delete(String id) throws Exception
    {   
        //get the session, if its not in memory, this will load it
        Session session = get(id, false); 

        //Always delete it from the backing data store
        if (_sessionDataStore != null)
        {
            boolean dsdel = _sessionDataStore.delete(id);
            if (LOG.isDebugEnabled()) LOG.debug("Session {} deleted in db {}",id, dsdel);                   
        }
        
        //delete it from the session object store
        return doDelete(id);
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
     * If the SessionDataStore supports passivation, 
     * write the session to the backing data store.
     * 
     * @param id identity of session to passivate
     */
    public void passivateIdleSession(String id)
    {
        if (!isStarted())
            return;
        
        if (_sessionDataStore == null || !_sessionDataStore.isPassivating())
            return; //no data store to passivate or it doesn't passivate 

        //get the session locally
        Session s = doGet(id);
        
        if (s == null)
        {
            LOG.warn("Session {} not in this session store", s);
            return;
        }


        //lock the session during passivation
        try (Lock lock = s.lock())
        {
            //check the session is still idle and that it doesn't have requests using it
            if (s.isValid() && s.isIdleLongerThan(_idlePassivationTimeoutSec) && s.isActive() && (s.getRequests() <= 0))
            {
                //TODO - do we need to check that the session exists in the session data store
                //before we passivate it? If it doesn't exist, we can assume another node 
                //invalidated it. If the session was new, it shouldn't have been idle passivated.
                try
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Passivating idle session {}", id);
                    s.willPassivate();
                    _sessionDataStore.store(id, s.getSessionData());
                    s.getSessionData().setDirty(false);
                    s.setPassivated();
                    doDelete(id); //Take the session object of this session store
                }
                catch (Exception e)
                {
                    LOG.warn("Passivation of idle session {} failed", id, e);
                    s.setPassivated(); //set it as passivated so it can't be used
                    doDelete(id); //detach it
                }
            }
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
