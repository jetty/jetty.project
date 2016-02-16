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


package org.eclipse.jetty.session.infinispan;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.infinispan.commons.api.BasicCache;

/**
 * InfinispanSessionDataStore
 *
 *
 */
public class InfinispanSessionDataStore extends AbstractSessionDataStore
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

    public static final int DEFAULT_IDLE_EXPIRY_MULTIPLE = 2;


    /**
     * Clustered cache of sessions
     */
    private BasicCache<String, Object> _cache;

    private SessionIdManager _idMgr = null;
    
    
    private int _idleExpiryMultiple = DEFAULT_IDLE_EXPIRY_MULTIPLE;
    

    /**
     * Get the clustered cache instance.
     * 
     * @return
     */
    public BasicCache<String, Object> getCache() 
    {
        return _cache;
    }

    
    
    /**
     * Set the clustered cache instance.
     * 
     * @param cache
     */
    public void setCache (BasicCache<String, Object> cache) 
    {
        this._cache = cache;
    }

    public void setSessionIdManager (SessionIdManager idMgr)
    {
        _idMgr = idMgr;
    }
    
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#load(org.eclipse.jetty.server.session.SessionKey)
     */
    @Override
    public SessionData load(String id) throws Exception
    {  
        final AtomicReference<SessionData> reference = new AtomicReference<SessionData>();
        final AtomicReference<Exception> exception = new AtomicReference<Exception>();
        
        Runnable load = new Runnable()
        {
            public void run ()
            {
                try
                {

                    SessionData sd = (SessionData)_cache.get(getCacheKey(id, _context));
                    reference.set(sd);
                }
                catch (Exception e)
                {
                    exception.set(e);
                }
            }
        };
        
        //ensure the load runs in the context classloader scope
        _context.run(load);
        
        if (exception.get() != null)
            throw exception.get();
        
        return reference.get();
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#delete(org.eclipse.jetty.server.session.SessionKey)
     */
    @Override
    public boolean delete(String id) throws Exception
    {
        return (_cache.remove(getCacheKey(id, _context)) != null);
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#getExpired(java.util.Set)
     */
    @Override
    public Set<String> getExpired(Set<String> candidates)
    {
       if (candidates == null  || candidates.isEmpty())
           return candidates;
       
       long now = System.currentTimeMillis();
       
       Set<String> expired = new HashSet<String>();
       if (LOG.isDebugEnabled())
           LOG.debug("Getting expired sessions " + now);
       
       for (String candidate:candidates)
       {
           try
           {
               SessionData sd = load(candidate);
               if (sd == null || sd.isExpiredAt(now))
                   expired.add(candidate);
                   
           }
           catch (Exception e)
           {
               LOG.warn("Error checking if session {} is expired", candidate, e);
           }
       }
       
       return expired;
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStore(org.eclipse.jetty.server.session.SessionKey, org.eclipse.jetty.server.session.SessionData, boolean)
     */
    @Override
    public void doStore(String id, SessionData data, boolean isNew) throws Exception
    {
        //Put an idle timeout on the cache entry if the session is not immortal - 
        //if no requests arrive at any node before this timeout occurs, or no node 
        //scavenges the session before this timeout occurs, the session will be removed.
        //NOTE: that no session listeners can be called for this.
        if (data.getMaxInactiveMs() > 0)
            _cache.put(getCacheKey(id, _context), data, -1, TimeUnit.MILLISECONDS, (data.getMaxInactiveMs() * _idleExpiryMultiple), TimeUnit.MILLISECONDS);
        else
            _cache.put(getCacheKey(id, _context), data);
        
        //tickle the session id manager to keep the sessionid entry for this session up-to-date
        if (_idMgr != null && _idMgr instanceof InfinispanSessionIdManager)
        {
            ((InfinispanSessionIdManager)_idMgr).touch(id);
        }
    }
    
    
    public static String getCacheKey (String id, SessionContext context)
    {
        return context.getCanonicalContextPath()+"_"+context.getVhost()+"_"+id;
    }



    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#isPassivating()
     */
    @Override
    public boolean isPassivating()
    {
        return true;
    }
}
