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


package org.eclipse.jetty.session.infinispan;

import org.eclipse.jetty.server.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.infinispan.commons.api.BasicCache;

/**
 * InfinispanSessionDataStoreFactory
 *
 *
 */
public class InfinispanSessionDataStoreFactory extends AbstractSessionDataStoreFactory
{
    int _infinispanIdleTimeoutSec;
    BasicCache<String, Object> _cache;

    
    /**
     * @return the infinispanIdleTimeoutSec
     */
    public int getInfinispanIdleTimeoutSec()
    {
        return _infinispanIdleTimeoutSec;
    }

    /**
     * @param infinispanIdleTimeoutSec the infinispanIdleTimeoutSec to set
     */
    public void setInfinispanIdleTimeoutSec(int infinispanIdleTimeoutSec)
    {
        _infinispanIdleTimeoutSec = infinispanIdleTimeoutSec;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStoreFactory#getSessionDataStore(org.eclipse.jetty.server.session.SessionHandler)
     */
    @Override
    public SessionDataStore getSessionDataStore (SessionHandler handler) throws Exception
    {
        InfinispanSessionDataStore store = new InfinispanSessionDataStore();
        store.setGracePeriodSec(getGracePeriodSec());
        store.setInfinispanIdleTimeoutSec(getInfinispanIdleTimeoutSec());
        store.setCache(getCache());
        return store;
    }
    
    /**
     * Get the clustered cache instance.
     * 
     * @return the cache
     */
    public BasicCache<String, Object> getCache() 
    {
        return _cache;
    }

    
    
    /**
     * Set the clustered cache instance.
     * 
     * @param cache the cache
     */
    public void setCache (BasicCache<String, Object> cache) 
    {
        this._cache = cache;
    }


}
