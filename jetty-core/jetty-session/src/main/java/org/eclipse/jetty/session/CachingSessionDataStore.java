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

package org.eclipse.jetty.session;

import java.util.Set;

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CachingSessionDataStore
 *
 * A SessionDataStore is a mechanism for (persistently) storing data associated with sessions.
 * This implementation delegates to a pluggable SessionDataStore for actually storing the
 * session data. It also uses a pluggable cache implementation in front of the
 * delegate SessionDataStore to improve performance: accessing most persistent store
 * technology can be expensive time-wise, so introducing a fronting cache
 * can increase performance. The cache implementation can either be a local cache,
 * a remote cache, or a clustered cache.
 *
 * The implementation here will try to read first from the cache and fallback to
 * reading from the SessionDataStore if the session key is not found. On writes, the
 * session data is written first to the SessionDataStore, and then to the cache. On
 * deletes, the data is deleted first from the SessionDataStore, and then from the
 * cache. There is no transaction manager ensuring atomic operations, so it is
 * possible that failures can result in cache inconsistency.
 */
public class CachingSessionDataStore extends ContainerLifeCycle implements SessionDataStore
{
    private static final Logger LOG = LoggerFactory.getLogger(CachingSessionDataStore.class);
    /**
     * The actual store for the session data
     */
    protected SessionDataStore _store;

    /**
     * The fronting cache
     */
    protected SessionDataMap _cache;

    /**
     * @param cache the front cache to use
     * @param store the actual store for the the session data
     */
    public CachingSessionDataStore(SessionDataMap cache, SessionDataStore store)
    {
        _cache = cache;
        addBean(_cache, true);
        _store = store;
        addBean(_store, true);
    }

    /**
     * @return the delegate session store
     */
    public SessionDataStore getSessionStore()
    {
        return _store;
    }

    /**
     * @return the fronting cache for session data
     */
    public SessionDataMap getSessionDataMap()
    {
        return _cache;
    }

    @Override
    public SessionData load(String id) throws Exception
    {
        SessionData d = null;

        try
        {
            //check to see if the session data is already in the cache
            d = _cache.load(id);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to load id {}", id, e);
        }

        if (d != null)
            return d; //cache hit

        //cache miss - go get it from the store
        d = _store.load(id);

        return d;
    }

    @Override
    public boolean delete(String id) throws Exception
    {
        //delete from the store
        boolean deleted = _store.delete(id);
        //and from the cache
        _cache.delete(id);

        return deleted;
    }

    @Override
    public Set<String> getExpired(Set<String> candidates)
    {
        //pass thru to the delegate store
        return _store.getExpired(candidates);
    }

    @Override
    public void store(String id, SessionData data) throws Exception
    {
        long lastSaved = data.getLastSaved();

        //write to the SessionDataStore first
        _store.store(id, data);

        //if the store saved it, then update the cache too
        if (data.getLastSaved() != lastSaved)
            _cache.store(id, data);
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

    @Override
    public boolean isPassivating()
    {
        return _store.isPassivating();
    }

    @Override
    public boolean exists(String id) throws Exception
    {
        try
        {
            //check the cache first
            SessionData data = _cache.load(id);
            if (data != null)
                return true;
        }
        catch (Exception e)
        {
            LOG.warn("Unable test exists on {}", id, e);
        }

        //then the delegate store
        return _store.exists(id);
    }

    @Override
    public void initialize(SessionContext context) throws Exception
    {
        //pass through
        _store.initialize(context);
        _cache.initialize(context);
    }

    @Override
    public SessionData newSessionData(String id, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        return _store.newSessionData(id, created, accessed, lastAccessed, maxInactiveMs);
    }
}
