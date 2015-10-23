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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * MemorySessionStore
 *
 *
 */
public class MemorySessionStore extends AbstractSessionStore
{
    private static final Logger LOG = Log.getLogger(MemorySessionStore.class);
    protected ConcurrentHashMap<String, Session> _sessions = new ConcurrentHashMap<String, Session>();

    
    
    public class MemorySession extends Session
    {

        /**
         * @param manager
         * @param request
         * @param data
         */
        public MemorySession(HttpServletRequest request, SessionData data)
        {
            super(request, data);
        }
        
        
        

        /**
         * @param manager
         * @param data
         */
        public MemorySession(SessionData data)
        {
            super(data);
        }
        
        
        
    }
    
    
    
    public MemorySessionStore ()
    {
       super();
    }
    
    
    
    
    
    
    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionStore#doGet(java.lang.String)
     */
    @Override
    public Session doGet(SessionKey key)
    {
        Session session = _sessions.get(key.getId());
        
        if (isStale(session))
        {
            //delete from memory so should reload
            doDelete(key);
            return null;
        }
        
        return session;
    }


    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionStore#doPut(java.lang.String, org.eclipse.jetty.server.session.Session)
     */
    @Override
    public void doPut(SessionKey key, Session session)
    {
        _sessions.put(key.getId(),  session);
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionStore#doExists(java.lang.String)
     */
    @Override
    public boolean doExists(SessionKey key)
    {
       return _sessions.containsKey(key.getId());
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionStore#doDelete(java.lang.String)
     */
    @Override
    public void doDelete(SessionKey key)
    {
        _sessions.remove(key.getId());
    }
    
    


    @Override
    public Set<SessionKey> doGetExpiredCandidates()
    {
        Set<SessionKey> candidates = new HashSet<SessionKey>();
        long now = System.currentTimeMillis();
        
        for (Session s:_sessions.values())
        {
            if (s.isExpiredAt(now))
                candidates.add(SessionKey.getKey(s.getId(), s.getContextPath(), s.getVHost()));
        }
        return candidates;
    }






    @Override
    public void shutdown ()
    {
        // loop over all the sessions in memory (a few times if necessary to catch sessions that have been
        // added while we're running
        int loop=100;
        while (!_sessions.isEmpty() && loop-- > 0)
        {
            for (Session session: _sessions.values())
            {
                //if we have a backing store and the session is dirty make sure it is written out
                if (_sessionDataStore != null)
                {
                    if (session.getSessionData().isDirty())
                    {
                        session.willPassivate();
                        try
                        {
                            _sessionDataStore.store(SessionKey.getKey(session.getSessionData()), session.getSessionData());
                        }
                        catch (Exception e)
                        {
                            LOG.warn(e);
                        }
                    }
                    doDelete (SessionKey.getKey(session.getSessionData())); //remove from memory
                }
                else
                {
                    //not preserving sessions on exit
                    try
                    {
                        session.invalidate();
                    }
                    catch (Exception e)
                    {
                        LOG.ignore(e);
                    }
                }
            }
        }
    }


    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#newSession(java.lang.String)
     */
    @Override
    public Session newSession(SessionKey key, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        //TODO - how to tell that the session is new?!
           return new MemorySession(_sessionDataStore.newSessionData(key, created, accessed, lastAccessed, maxInactiveMs));
    }




    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionStore#newSession(org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public Session newSession(SessionData data)
    {
        return new MemorySession (data);
    }


}
