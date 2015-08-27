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
     * @see org.eclipse.jetty.server.session.x.AbstractSessionStore#doGet(java.lang.String)
     */
    @Override
    public Session doGet(String id)
    {
        Session session = _sessions.get(id);
        
        if (isStale(session))
        {
            //delete from memory
            doDelete(id);
            return null;
        }
        
        return session;
    }

    /** 
     * @see org.eclipse.jetty.server.session.x.AbstractSessionStore#doPutIfAbsent(java.lang.String, org.eclipse.jetty.server.session.x.Session)
     */
    @Override
    public Session doPutIfAbsent(String id, Session session)
    {
        return _sessions.putIfAbsent(id, session);
    }

    /** 
     * @see org.eclipse.jetty.server.session.x.AbstractSessionStore#doPut(java.lang.String, org.eclipse.jetty.server.session.x.Session)
     */
    @Override
    public void doPut(String id, Session session)
    {
        _sessions.put(id,  session);
    }

    /** 
     * @see org.eclipse.jetty.server.session.x.AbstractSessionStore#doExists(java.lang.String)
     */
    @Override
    public boolean doExists(String id)
    {
       return _sessions.containsKey(id);
    }

    /** 
     * @see org.eclipse.jetty.server.session.x.AbstractSessionStore#doDelete(java.lang.String)
     */
    @Override
    public void doDelete(String id)
    {
        _sessions.remove(id);
    }

    @Override
    public void shutdown ()
    {
        
        //TODO Always have a sessionDataStore, but it may be the null store!
        
        
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
                            _sessionDataStore.store(session.getId(), session.getSessionData());
                        }
                        catch (Exception e)
                        {
                            LOG.warn(e);
                        }
                    }
                    doDelete (session.getId()); //remove from memory
                }
                else
                {
                    //TODO this will call back into our delete method
                    //not preserving sessions on exit
                    session.invalidate();
                }
            }
        }
    }


    /** 
     * @see org.eclipse.jetty.server.session.x.SessionStore#newSession(java.lang.String)
     */
    @Override
    public Session newSession(String id, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
           return new MemorySession(_sessionDataStore.newSessionData(id, created, accessed, lastAccessed, maxInactiveMs));
    }




    /** 
     * @see org.eclipse.jetty.server.session.x.AbstractSessionStore#newSession(org.eclipse.jetty.server.session.x.SessionData)
     */
    @Override
    public Session newSession(SessionData data)
    {
        return new MemorySession (data);
    }

}
