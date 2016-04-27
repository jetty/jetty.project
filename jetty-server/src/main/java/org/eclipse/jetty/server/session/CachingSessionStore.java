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

import java.util.Set;

import org.eclipse.jetty.util.component.AbstractLifeCycle;

/**
 * CachingSessionStore
 *
 * A SessionStore is a mechanism for (persistently) storing data associated with sessions.
 * This implementation delegates to a pluggable SessionStore for actually storing the 
 * session data. It also uses a pluggable cache implementation in front of the 
 * delegate SessionStore to improve performance: accessing most persistent store
 * technology can be expensive time-wise, so introducing a fronting cache 
 * can increase performance. The cache implementation can either be a local cache, 
 * a remote cache, or a clustered cache.
 */
public class CachingSessionStore extends AbstractLifeCycle implements SessionStore
{

    /**
     * Cache
     *
     * An interface that represents the contract with the particular cache
     * implementation, eg memcache, infinispan etc
     */
    public interface Cache
    {
        public SessionData get (String id); //get cached value
        public boolean putIfAbsent (String id, SessionData data); //only insert if no mapping for key already
        public boolean remove (String id); //remove the mapping for key, returns false if no mapping
        public void put (String id, SessionData data); //overwrite or add the mapping
        public void initialize(SessionContext context);
    }
    
    
    protected SessionStore _store;
    protected Cache _cache;
    
    
    /**
     * @param cache
     * @param store
     */
    public CachingSessionStore (Cache cache, SessionStore store)
    {
        _cache = cache;
        _store = store;
    }
   
    
    /**
     * @return
     */
    public SessionStore getSessionStore()
    {
        return _store;
    }
    
    
    
    /**
     * @return
     */
    public Cache getSessionStoreCache ()
    {
        return _cache;
    }
    
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#load(java.lang.String)
     */
    @Override
    public SessionData load(String id) throws Exception
    {
        //check to see if the session data is already in the cache
        SessionData d = _cache.get(id);
        if (d == null)
        {
            //not in the cache, go get it from the store
            d =  _store.load(id);

            if (d != null)
            {
                //put it into the cache, unless another thread/node has put it into the cache
                boolean inserted = _cache.putIfAbsent(id, d);
                if (!inserted)
                {
                    //some other thread/node put this data into the cache, so get it from there
                    SessionData d2 = _cache.get(id);

                    if (d2 != null)
                        d = d2;
                    //else: The cache either timed out the entry, or maybe the session data was being removed, and we're about to resurrect it!
                }
            }
        }
        return d;
    }


    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#delete(java.lang.String)
     */
    @Override
    public boolean delete(String id) throws Exception
    {
        //delete from the store and from the cache
        boolean deleted = _store.delete(id);
        
        //TODO what to do if couldn't remove from the cache?
        _cache.remove(id);
        
        return deleted;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#getExpired(Set)
     */
    @Override
    public Set<String> getExpired(Set<String> candidates)
    {
        //pass thru to the delegate store
        return _store.getExpired(candidates);
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#store(java.lang.String, org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public void store(String id, SessionData data) throws Exception
    {
        //write to the SessionDataStore first
        _store.store(id, data);
        
        //then update the cache with written data
        _cache.put(id,data);
    }

  

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#isPassivating()
     */
    @Override
    public boolean isPassivating()
    {
       return _store.isPassivating();
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#exists(java.lang.String)
     */
    @Override
    public boolean exists(String id) throws Exception
    {
        //check the cache first
        SessionData data = _cache.get(id);
        if (data != null)
            return true;
        
        //then the delegate store
        return _store.exists(id);
    }


    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#initialize(org.eclipse.jetty.server.session.SessionContext)
     */
    @Override
    public void initialize(SessionContext context)
    {
        //pass through
        _store.initialize(context);
        _cache.initialize(context);
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionStore#newSessionData(java.lang.String, long, long, long, long)
     */
    @Override
    public SessionData newSessionData(String id, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        return _store.newSessionData(id, created, accessed, lastAccessed, maxInactiveMs);
    }


}
