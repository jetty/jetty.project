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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.statistic.CounterStatistic;

/**
 * MemorySessionStore
 *
 * A session store that keeps its sessions in memory in a hashmap
 */
public class MemorySessionStore extends AbstractSessionStore
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    
    protected ConcurrentHashMap<String, Session> _sessions = new ConcurrentHashMap<String, Session>();
    
    private final CounterStatistic _stats = new CounterStatistic();
    
    
    /**
     * MemorySession
     *
     *
     */
    public class MemorySession extends Session
    {
        /**
         * @param request the request associated with the new session
         * @param data the info for the session
         */
        public MemorySession(HttpServletRequest request, SessionData data)
        {
            super(request, data);
        }
        
        
        /**
         * @param data the info for the restored session object
         */
        public MemorySession(SessionData data)
        {
            super(data);
        }
    }
    
    
    
    public MemorySessionStore (SessionManager manager)
    {
        super (manager);
    }
    
    
    public long getSessions ()
    {
        return _stats.getCurrent();
    }
    
    
    public long getSessionsMax()
    {
        return _stats.getMax();
    }
    
    
    public long getSessionsTotal()
    {
        return _stats.getTotal();
    }
    
    public void resetStats()
    {
        _stats.reset();
    }
    
    
    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionStore#doGet(java.lang.String)
     */
    @Override
    public Session doGet(String id)
    {
        if (id == null)
            return null;
        
        Session session = _sessions.get(id);
       
        return session;
    }


    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionStore#doPutIfAbsent(java.lang.String, org.eclipse.jetty.server.session.Session)
     */
    @Override
    public Session doPutIfAbsent(String id, Session session)
    {
        Session s = _sessions.putIfAbsent(id, session);
        if (s == null && !(session instanceof PlaceHolderSession))
            _stats.increment();
       return s;
    }

  

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionStore#doDelete(java.lang.String)
     */
    @Override
    public Session doDelete(String id)
    {
        Session s = _sessions.remove(id);
        if (s != null && !(s instanceof PlaceHolderSession))
            _stats.decrement();
        return  s;
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
     * @see org.eclipse.jetty.server.session.AbstractSessionStore#newSession(javax.servlet.http.HttpServletRequest, org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public Session newSession(HttpServletRequest request, SessionData data)
    {
        MemorySession s =  new MemorySession(request,data);
        return s;
    }




    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionStore#newSession(org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public Session newSession(SessionData data)
    {
        MemorySession s = new MemorySession (data);
        return s;
    }




    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionStore#doReplace(java.lang.String, org.eclipse.jetty.server.session.Session, org.eclipse.jetty.server.session.Session)
     */
    @Override
    public boolean doReplace(String id, Session oldValue, Session newValue)
    {
        boolean result = _sessions.replace(id,  oldValue, newValue);
        if (result && (oldValue instanceof PlaceHolderSession))
            _stats.increment();
        return result;
    }


}
