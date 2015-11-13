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

import java.util.Set;

/**
 * CachingSessionDataStore
 *
 * A SessionDataStore is a mechanism for (persistently) storing data associated with sessions.
 * This implementation delegates to a pluggable SessionDataStore for actually storing the 
 * session data. It also uses a pluggable JCache implementation in front of the 
 * delegate SessionDataStore to improve performance: accessing most persistent store
 * technology can be expensive time-wise, so introducing a fronting cache 
 * can increase performance. The cache implementation can either be a local cache, 
 * a remote cache, or a clustered cache.
 */
public class CachingSessionDataStore extends AbstractSessionDataStore
{

    public interface SessionDataCache
    {
        public SessionData get (SessionKey key); //get mapped value
        public boolean putIfAbsent (SessionKey key, SessionData data); //only insert if no mapping for key already
        public boolean remove (SessionKey key); //remove the mapping for key, returns false if no mapping
        public void put (SessionKey key, SessionData data); //overwrite or add the mapping
    }
    
    
    protected SessionDataStore _delegateDataStore;
    protected SessionDataCache _cache;
    
    
    public void setSessionDataStore (SessionDataStore store)
    {
        checkStarted();
        _delegateDataStore = store;
    }
    
    public SessionDataStore getSessionDataStore()
    {
        return _delegateDataStore;
    }
    
    
    public void setSessionDataCache (SessionDataCache cache)
    {
        checkStarted();
        _cache = cache;
    }
    
    public SessionDataCache getSessionDataCache ()
    {
        return _cache;
    }
    
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#load(org.eclipse.jetty.server.session.SessionKey)
     */
    @Override
    public SessionData load(SessionKey key) throws Exception
    {
        //check to see if the session data is already in our cache
        SessionData d = _cache.get(key);
        if (d == null)
        {
            //not in the cache, go get it from the store
           d =  _delegateDataStore.load(key);
           
           //put it into the cache, unless another thread/node has put it into the cache
          boolean inserted = _cache.putIfAbsent(key, d);
          if (!inserted)
          {
              //some other thread/node put this data into the cache, so get it from there
              SessionData d2 = _cache.get(key);
              
              if (d2 != null)
                  d = d2;
             //else: The cache either timed out the entry, or maybe the session data was being removed, and we're about to resurrect it!
          }
        }
        return d;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#delete(org.eclipse.jetty.server.session.SessionKey)
     */
    @Override
    public boolean delete(SessionKey key) throws Exception
    {
        //delete from the store and from the cache
        _delegateDataStore.delete(key);
        _cache.remove(key);
        //TODO need to check removal at each level?
        return false;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#getExpired(java.util.Set)
     */
    @Override
    public Set<SessionKey> getExpired(Set<SessionKey> candidates)
    {
        // TODO Auto-generated method stub
        return null;
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStore(org.eclipse.jetty.server.session.SessionKey, org.eclipse.jetty.server.session.SessionData, boolean)
     */
    @Override
    public void doStore(SessionKey key, SessionData data, boolean isNew) throws Exception
    {
        //write to the SessionDataStore first
        if (_delegateDataStore instanceof AbstractSessionDataStore)
            ((AbstractSessionDataStore)_delegateDataStore).doStore(key, data, isNew);

        //else??????
        
        //then update the cache with written data
        _cache.put(key,data);

    }

}
