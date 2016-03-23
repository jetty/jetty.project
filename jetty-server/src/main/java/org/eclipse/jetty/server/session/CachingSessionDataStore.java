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
        public SessionData get (String id); //get mapped value
        public boolean putIfAbsent (String id, SessionData data); //only insert if no mapping for key already
        public boolean remove (String id); //remove the mapping for key, returns false if no mapping
        public void put (String id, SessionData data); //overwrite or add the mapping
        public void initialize(SessionContext context);
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
     * @see org.eclipse.jetty.server.session.SessionDataStore#load(java.lang.String)
     */
    @Override
    public SessionData load(String id) throws Exception
    {
        //check to see if the session data is already in our cache
        SessionData d = _cache.get(id);
        if (d == null)
        {
            //not in the cache, go get it from the store
           d =  _delegateDataStore.load(id);
           
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
        return d;
    }


    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#delete(java.lang.String)
     */
    @Override
    public boolean delete(String id) throws Exception
    {
        //delete from the store and from the cache
        _delegateDataStore.delete(id);
        _cache.remove(id);
        //TODO need to check removal at each level?
        return false;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#getExpired(Set)
     */
    @Override
    public Set<String> doGetExpired(Set<String> candidates)
    {
        // TODO Auto-generated method stub
        return null;
    }


    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStore(java.lang.String, org.eclipse.jetty.server.session.SessionData, long)
     */
    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        //write to the SessionDataStore first
        if (_delegateDataStore instanceof AbstractSessionDataStore)
            ((AbstractSessionDataStore)_delegateDataStore).doStore(id, data, lastSaveTime);

        //else??????
        
        //then update the cache with written data
        _cache.put(id,data);

    }

    @Override
    protected void doStart() throws Exception
    {
        _cache.initialize(_context);
        _delegateDataStore.initialize(_context);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        // TODO Auto-generated method stub
        super.doStop();
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#isPassivating()
     */
    @Override
    public boolean isPassivating()
    {
       return true;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#exists(java.lang.String)
     */
    @Override
    public boolean exists(String id) throws Exception
    {
        // TODO Auto-generated method stub
        return false;
    }
}
