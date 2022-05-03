//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.session.infinispan;

import org.eclipse.jetty.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.session.SessionDataStore;
import org.eclipse.jetty.session.SessionManager;
import org.infinispan.commons.api.BasicCache;

/**
 * InfinispanSessionDataStoreFactory
 */
public class InfinispanSessionDataStoreFactory extends AbstractSessionDataStoreFactory
{
    int _infinispanIdleTimeoutSec;
    BasicCache<String, InfinispanSessionData> _cache;
    protected QueryManager _queryManager;
    protected boolean _serialization;

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

    @Override
    public SessionDataStore getSessionDataStore(SessionManager sessionManager) throws Exception
    {
        InfinispanSessionDataStore store = new InfinispanSessionDataStore();
        store.setGracePeriodSec(getGracePeriodSec());
        store.setInfinispanIdleTimeoutSec(getInfinispanIdleTimeoutSec());
        store.setCache(getCache());
        store.setSavePeriodSec(getSavePeriodSec());
        store.setQueryManager(getQueryManager());
        store.setSerialization(getSerialization());
        return store;
    }

    /**
     * Get the clustered cache instance.
     *
     * @return the cache
     */
    public BasicCache<String, InfinispanSessionData> getCache()
    {
        return _cache;
    }

    /**
     * Set the clustered cache instance.
     *
     * @param cache the cache
     */
    public void setCache(BasicCache<String, InfinispanSessionData> cache)
    {
        this._cache = cache;
    }

    public QueryManager getQueryManager()
    {
        return _queryManager;
    }

    public void setQueryManager(QueryManager queryManager)
    {
        _queryManager = queryManager;
    }
    
    public void setSerialization(boolean serialization)
    {
        _serialization = serialization;
    }
    
    public boolean getSerialization()
    {
        return _serialization;
    }
}
