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

import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.statistic.CounterStatistic;

/**
 * DefaultSessionCache
 *
 * A session store that keeps its sessions in memory in a hashmap
 */
public class DefaultSessionCache extends AbstractSessionCache
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    
    /**
     * The cache of sessions in a hashmap
     */
    protected ConcurrentHashMap<String, Session> _sessions = new ConcurrentHashMap<String, Session>();
    
    private final CounterStatistic _stats = new CounterStatistic();
    
    
    
    /**
     * @param manager
     */
    public DefaultSessionCache (SessionHandler manager)
    {
        super (manager);
    }
    
    
    /**
     * @return the number of sessions in the cache
     */
    public long getSessionsCurrent ()
    {
        return _stats.getCurrent();
    }
    
    
    /**
     * @return the max number of sessions in the cache
     */
    public long getSessionsMax()
    {
        return _stats.getMax();
    }
    
    
    /**
     * @return a running total of sessions in the cache
     */
    public long getSessionsTotal()
    {
        return _stats.getTotal();
    }
    
    /**
     * 
     */
    public void resetStats()
    {
        _stats.reset();
    }
    
    
    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionCache#doGet(java.lang.String)
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
     * @see org.eclipse.jetty.server.session.AbstractSessionCache#doPutIfAbsent(java.lang.String, org.eclipse.jetty.server.session.Session)
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
     * @see org.eclipse.jetty.server.session.AbstractSessionCache#doDelete(java.lang.String)
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
     * @see org.eclipse.jetty.server.session.AbstractSessionCache#newSession(javax.servlet.http.HttpServletRequest, org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public Session newSession(HttpServletRequest request, SessionData data)
    {
        Session s =  new Session(getSessionHandler(),request, data);
        return s;
    }




    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionCache#newSession(org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public Session newSession(SessionData data)
    {
        Session s = new Session (getSessionHandler(), data);
        return s;
    }




    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionCache#doReplace(java.lang.String, org.eclipse.jetty.server.session.Session, org.eclipse.jetty.server.session.Session)
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
