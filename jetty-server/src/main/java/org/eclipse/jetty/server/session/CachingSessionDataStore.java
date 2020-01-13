//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
    private static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
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

    /**
     * @see org.eclipse.jetty.server.session.SessionDataStore#load(java.lang.String)
     */
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
            LOG.warn(e);
        }

        if (d != null)
            return d; //cache hit

        //cache miss - go get it from the store
        d = _store.load(id);

        return d;
    }

    /**
     * @see org.eclipse.jetty.server.session.SessionDataStore#delete(java.lang.String)
     */
    @Override
    public boolean delete(String id) throws Exception
    {
        //delete from the store
        boolean deleted = _store.delete(id);
        //and from the cache
        _cache.delete(id);

        return deleted;
    }

    /**
     * @see org.eclipse.jetty.server.session.SessionDataStore#getExpired(Set)
     */
    @Override
    public Set<String> getExpired(Set<String> candidates)
    {
        //pass thru to the delegate store
        return _store.getExpired(candidates);
    }

    /**
     * @see org.eclipse.jetty.server.session.SessionDataStore#store(java.lang.String, org.eclipse.jetty.server.session.SessionData)
     */
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

    /**
     * @see org.eclipse.jetty.server.session.SessionDataStore#isPassivating()
     */
    @Override
    public boolean isPassivating()
    {
        return _store.isPassivating();
    }

    /**
     * @see org.eclipse.jetty.server.session.SessionDataStore#exists(java.lang.String)
     */
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
            LOG.warn(e);
        }

        //then the delegate store
        return _store.exists(id);
    }

    /**
     * @see org.eclipse.jetty.server.session.SessionDataStore#initialize(org.eclipse.jetty.server.session.SessionContext)
     */
    @Override
    public void initialize(SessionContext context) throws Exception
    {
        //pass through
        _store.initialize(context);
        _cache.initialize(context);
    }

    /**
     * @see org.eclipse.jetty.server.session.SessionDataStore#newSessionData(java.lang.String, long, long, long, long)
     */
    @Override
    public SessionData newSessionData(String id, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        return _store.newSessionData(id, created, accessed, lastAccessed, maxInactiveMs);
    }
}
