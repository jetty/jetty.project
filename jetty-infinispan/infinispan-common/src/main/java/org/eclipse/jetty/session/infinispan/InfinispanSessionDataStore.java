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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.UnreadableSessionDataException;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.infinispan.commons.api.BasicCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InfinispanSessionDataStore
 *
 *
 */
@ManagedObject
public class InfinispanSessionDataStore extends AbstractSessionDataStore
{
    private static final Logger LOG = LoggerFactory.getLogger(InfinispanSessionDataStore.class);

    /**
     * Clustered cache of sessions
     */
    private BasicCache<String, InfinispanSessionData> _cache;
    private int _infinispanIdleTimeoutSec;
    private QueryManager _queryManager;
    private boolean _passivating;
    private boolean _serialization;
    
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

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        if (_cache == null)
            throw new IllegalStateException("No cache");

        try 
        {
            Class<?> remoteClass = InfinispanSessionDataStore.class.getClassLoader().loadClass("org.infinispan.client.hotrod.RemoteCache");
            if (remoteClass.isAssignableFrom(_cache.getClass()) || _serialization)
                _passivating = true;
        }
        catch (ClassNotFoundException e)
        {
            //expected if not running with remote cache
            LOG.info("Hotrod classes not found, assuming infinispan in embedded mode");
        }
    }

    public QueryManager getQueryManager()
    {
        return _queryManager;
    }

    public void setQueryManager(QueryManager queryManager)
    {
        _queryManager = queryManager;
    }

    @Override
    public SessionData doLoad(String id) throws Exception
    {  
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Loading session {} from infinispan", id);

            InfinispanSessionData sd = _cache.get(getCacheKey(id));
            
            //Deserialize the attributes now that we are back in a thread that
            //has the correct classloader set on it
            if (isPassivating() && sd != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Deserializing session attributes for {}", id);
                sd.deserializeAttributes();
            }

            return sd;
        }
        catch (Exception e)
        {
            throw new UnreadableSessionDataException(id, _context, e);
        }
    }

    @Override
    public boolean delete(String id) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Deleting session with id {} from infinispan", id);
        return (_cache.remove(getCacheKey(id)) != null);
    }

    @Override
    public Set<String> doCheckExpired(Set<String> candidates, long time)
    {
        if (candidates == null  || candidates.isEmpty())
            return candidates;

        Set<String> expired = new HashSet<>();
   
        for (String candidate:candidates)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Checking expiry for candidate {}", candidate);
            try
            {
                SessionData sd = load(candidate);

                //if the session no longer exists
                if (sd == null)
                {
                    expired.add(candidate);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} does not exist in infinispan", candidate);
                }
                else
                {
                    if (_context.getWorkerName().equals(sd.getLastNode()))
                    {
                        //we are its manager, add it to the expired set if it is expired now
                        if ((sd.getExpiry() > 0) && sd.getExpiry() <= time)
                        {
                            expired.add(candidate);
                            if (LOG.isDebugEnabled())
                                LOG.debug("Session {} managed by {} is expired", candidate, _context.getWorkerName());
                        }
                    }
                }
            }
            catch (Exception e)
            {
                LOG.warn("Error checking if candidate {} is expired", candidate, e);
            }
        }
        return expired;
    }
    
    @Override
    public Set<String> doGetExpired(long time)
    {
        //If there is a query manager, find the sessions for our context that expired before the time limit
        if (_queryManager != null)
        {
            Set<String> expired = new HashSet<>();
            for (String sessionId : _queryManager.queryExpiredSessions(_context, time))
            {
                expired.add(sessionId);
                if (LOG.isDebugEnabled())
                    LOG.debug("{}- Found expired sessionId={}", _context.getWorkerName(), sessionId);
            }
            return expired;
        }
        return Collections.emptySet();
    }

    @Override
    public void doCleanOrphans(long timeLimit)
    {
        //if there is a query manager, find the sessions for any context that expired before the time limit and delete
        if (_queryManager != null)
            _queryManager.deleteOrphanSessions(timeLimit);
        else
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to clean orphans, no QueryManager");
    }

    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        //prepare for serialization: we need to convert the attributes now while the context
        //classloader is set, because infinispan uses a different thread and classloader to
        //perform the serialization
        if (isPassivating() && data != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Serializing session attributes for {}", id);
            ((InfinispanSessionData)data).serializeAttributes();
        }
        //Put an idle timeout on the cache entry if the session is not immortal - 
        //if no requests arrive at any node before this timeout occurs, or no node 
        //scavenges the session before this timeout occurs, the session will be removed.
        //NOTE: that no session listeners can be called for this.
        if (data.getMaxInactiveMs() > 0 && getInfinispanIdleTimeoutSec() > 0)
            _cache.put(getCacheKey(id), (InfinispanSessionData)data, -1, TimeUnit.MILLISECONDS, getInfinispanIdleTimeoutSec(), TimeUnit.SECONDS);
        else
            _cache.put(getCacheKey(id), (InfinispanSessionData)data);

        if (LOG.isDebugEnabled())
            LOG.debug("Session {} saved to infinispan, expires {} ", id, data.getExpiry());
    }
    
    public String getCacheKey(String id)
    {
        return InfinispanKeyBuilder.build(_context.getCanonicalContextPath(), _context.getVhost(), id);
    }

    @ManagedAttribute(value = "does store serialize sessions", readonly = true)
    @Override
    public boolean isPassivating()
    {
        return _passivating;
    }

    @Override
    public boolean doExists(String id) throws Exception
    {
        //if we have a query manager we can do a query with a projection to check
        //if there is an unexpired session with the given id
        if (_queryManager != null)
            return _queryManager.exists(_context, id);
        else
        {
            //no query manager, load the entire session data object
            SessionData sd = doLoad(id);
            if (sd == null)
                return false;

            if (sd.getExpiry() <= 0)
                return true;
            else
                return (sd.getExpiry() > System.currentTimeMillis()); //not expired yet
        }
    }

    @Override
    public SessionData newSessionData(String id, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        return new InfinispanSessionData(id, _context.getCanonicalContextPath(), _context.getVhost(), created, accessed, lastAccessed, maxInactiveMs);
    }

    /**
     * @param sec the infinispan-specific idle timeout in sec or 0 if not set
     */
    public void setInfinispanIdleTimeoutSec(int sec)
    {
        _infinispanIdleTimeoutSec = sec;
    } 
    
    @ManagedAttribute(value = "infinispan idle timeout sec", readonly = true)
    public int getInfinispanIdleTimeoutSec()
    {
        return _infinispanIdleTimeoutSec;
    }
    
    public void setSerialization(boolean serialization)
    {
        _serialization = serialization;
    }

    @Override
    public String toString()
    {
        return String.format("%s[cache=%s,idleTimeoutSec=%d]", super.toString(), (_cache == null ? "" : _cache.getName()), _infinispanIdleTimeoutSec);
    }
}
