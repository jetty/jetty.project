//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.util.thread.Locker.Lock;


/**
 * ReferenceTrackingSessionCache
 *
 * This is a session cache that does not actually share Session instances. In this
 * respect it is like the NullSessionCache: every time
 * get(id) is called, the session data is freshly loaded from the
 * SessionDataStore and a new Session instance is created.  Unlike the
 * NullSessionCache, this cache maintains references to each
 * Session that is returned.  This allows the cache to respond to
 * invalidation and session id renewal by ensuring that all in-use Session objects
 * will be updated. This cache would be of use in a non-sticky load
 * balancer situation where session data is very likely to change on
 * another node, but you still want to ensure that if any Request on this
 * node invalidates a Session, or changes the id of the Session, that
 * all other copies of the Session are similarly invalidated or changed and
 * the appropriate listeners called.
 */
public class ReferenceTrackingSessionCache extends NullSessionCache
{
    protected Map<String, Set<Session>> _references = new HashMap<>();
  
    
    
    /**
     * @param handler The SessionHandler related to this SessionCache
     */
    public ReferenceTrackingSessionCache(SessionHandler handler)
    {
        super(handler);
        super.setEvictionPolicy(EVICT_ON_SESSION_EXIT);
    }

    @Override
    public void shutdown()
    {
        _references.clear();
    }



    @Override
    public Session doDelete(String id)
    {
        synchronized (_references)
        {
            _references.remove(id);
        }
        return null;
    }
    
    

    @Override
    public Session delete(String id) throws Exception
    { 
        //Always delete it from the backing data store
        if (_sessionDataStore != null)
        {
            boolean dsdel = _sessionDataStore.delete(id);
            if (LOG.isDebugEnabled()) LOG.debug("Session {} deleted in session data store {}",id, dsdel);                   
        }
        
        Set<Session> sessions = null;
        
        synchronized (_references)
        {            
           sessions = _references.remove(id); //delete all references to sessions
        }
        
        if (sessions != null)
        {
            for (Session s:sessions)
            {
                try (Lock lock = s.lock())
                {
                    try
                    {
                        if (s.beginInvalidate()) //session has not already begun being invalidated, so its not the one on which session.invalidate was called
                        {
                            s.setResident(false);
                            _handler.callSessionListeners(s);                  
                            s.finishInvalidate();
                        }
                    }
                    catch (IllegalStateException e)
                    {
                        //the session on which invalidate was called will already be invalid
                        LOG.ignore(e);
                    }
                }
            }
            return null; //don't return a session, they've all been handled already either here or on the session that was originally invalidated
        }
        else
        {
            //no sessions with that id are being referenced by a request
            //load one, just to call the listeners
            Session session = loadSession(id);
            if (session == null) //so such session
                return null;

            try (Lock lock = session.lock())
            {
                session.setResident(false); //don't put it in the reference list
            }
            return session; //let the SessionHandler handle the invalidation
        }
    }
        


    @Override
    public Session get(String id) throws Exception
    {
        //Always load a fresh session from the backing sessiondatastore
        if (_sessionDataStore == null)
            return null;

        Session session = loadSession(id);
        if (session != null)
        {
            session.setResident(true);
            addReference(session);
        }
        return session;
    }



    @Override
    public void put(String id, Session session) throws Exception
    {
        //A request is finished with the session,remove it from the references
        synchronized (_references)
        {
            Set<Session> sessions = _references.get(id);
            if (sessions != null &&  sessions.contains(session))
            {
                if (LOG.isDebugEnabled()) LOG.debug("Existing session {} put back into cache", session);
                
                //session exists, so the request is finished with it
                sessions.remove(session);

                if (sessions.isEmpty())
                    _references.remove(id); //last reference to that id is removed
                
                
                //if the session isn't valid, don't save it
                if (!session.isValid())
                    return; 
                //save the session
                if (!_sessionDataStore.isPassivating())
                {
                    //if our backing datastore isn't the passivating kind, just save the session
                    _sessionDataStore.store(id, session.getSessionData());
                    session.setResident(false);
                }
                else
                {
                    //backing store supports passivation, call the listeners
                    session.willPassivate();
                    if (LOG.isDebugEnabled()) LOG.debug("Session passivating id={}", id);
                    _sessionDataStore.store(id, session.getSessionData());
                    session.setResident(false); 
                }
            }
            else
            {
                if (LOG.isDebugEnabled()) LOG.debug("New session {} added to ReferenceTrackingSessionCache", session);
                //no existing sessions for this id at all, or this precise session is not in use by a request
                addReference(session); 
                session.setResident(true); 
            }
        } 
    }

    
    /**
     * Returns number of session instances refering to the same id.
     * 
     * @param id the session id to check
     * @return The number of references for the same session id.
     */
    protected int references (String id)
    {
        synchronized (_references)
        {
            Set<Session> sessions = _references.get(id);
            if (sessions == null)
                return 0;
            return sessions.size();
        }
    }
    
    
    
    @Override
    public Session renewSessionId(String oldId, String newId, String oldExtendedId, String newExtendedId) throws Exception
    {
        Session firstValidSession = null;

        synchronized (_references)
        {
            //delete the old key
            Set<Session> sessions = _references.remove(oldId);
            
            //if there are existing references to sessions in use by requests
            if (sessions != null)
            {
                boolean written = false;
                for (Session session: sessions)
                {
                    try (Lock lock = session.lock())
                    {
                        //change the id over
                        renewSessionId (session, newId, newExtendedId);

                        //A random session is the one that is written out!! TODO
                        if (!written)
                        {
                            //only update the database with the first one
                            written = true;
                            writeNewSessionId(session, oldId, newId);
                        }
                        //call the listeners on all existing sessions
                        _handler.callSessionIdListeners(session, oldId);
                    }
                    catch (IllegalStateException e)
                    {
                        //If the session is not valid for writing, ignore changing its id
                        LOG.warn("Session with old id={} invalid for writing, skipping update to new id={}", oldId, newId);
                    }
                }

                //update all the references
                _references.put(newId, sessions); 
                return null; //all sessions handled here
            }
            else
            {
                //there are no sessions loaded for this id in this context, we need to load one so we can change it
                //but do NOT put it in the reference map because no request is accessing it. Return null;               
                Session session = loadSession(oldId);
                if (session != null)
                {
                    //session with this old id exists
                    try (Lock lock = session.lock())
                    {
                        renewSessionId(firstValidSession, newId, newExtendedId);
                        writeNewSessionId(firstValidSession, oldId, newId);
                    }
                    _handler.callSessionIdListeners(session, oldId);
                }
                return null;
            }
        }       
    }
    
    

    @Override
    protected void renewSessionId(Session session, String newId, String newExtendedId) throws Exception
    {
        try (Lock lock = session.lock())
        {
            session.checkValidForWrite(); //can't change id on invalid session
            session.getSessionData().setId(newId);
            session.getSessionData().setLastSaved(0); //pretend that the session has never been saved before to get a full save
            session.getSessionData().setDirty(true);  //ensure we will try to write the session out    
            session.setExtendedId(newExtendedId); //remember the new extended id
            session.setIdChanged(true); //session id changed
        }
    }
    
    
    /**
     * Change the session id in the store.
     * 
     * @param session the session to change
     * @param oldId the old session id
     * @param newId the new session id to save it as
     * @throws Exception
     */
    protected void writeNewSessionId (Session session, String oldId, String newId)
    throws Exception
    {
        if (_sessionDataStore != null)
        {
            _sessionDataStore.delete(oldId);  //delete the session data with the old id
            _sessionDataStore.store(newId, session.getSessionData()); //save the session data with the new id
        }
        if (LOG.isDebugEnabled())
            LOG.debug ("Session id {} swapped for new id {}", oldId, newId);
    }


    
    /**
     * Add a new reference to a session.
     * 
     * @param session the newly created session to remember
     */
    protected void addReference(Session session)
    {
        synchronized (_references)
        {        
            Set<Session> sessions = _references.get(session.getId());
            if (sessions == null)
            {
                sessions = new HashSet<>();
                _references.put(session.getId(), sessions);
            }

            sessions.add(session);
            if (LOG.isDebugEnabled()) LOG.debug("Added new session {} to references for id={}",session, session.getId());
        }
    }
}
