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

package org.eclipse.jetty.hazelcast.session;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.hazelcast.config.IndexConfig;
import com.hazelcast.config.IndexType;
import com.hazelcast.map.IMap;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.PredicateBuilder.EntryObject;
import com.hazelcast.query.Predicates;
import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.UnreadableSessionDataException;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session data stored in Hazelcast
 */
@ManagedObject
public class HazelcastSessionDataStore extends AbstractSessionDataStore
    implements SessionDataStore
{

    private static final Logger LOG = LoggerFactory.getLogger(HazelcastSessionDataStore.class);

    private IMap<String, SessionData> sessionDataMap;

    private boolean _useQueries;

    public HazelcastSessionDataStore()
    {
    }

    /**
     * Control whether or not to execute queries to find
     * expired sessions - ie sessions for this context 
     * that are no longer actively referenced by any jetty 
     * instance and should be expired.
     *
     * If you use this feature, be aware that if your session
     * stores any attributes that use classes from within your
     * webapp, or from within jetty, you will need to make sure
     * those classes are available to all of your hazelcast
     * instances, whether embedded or remote.
     *
     * @param useQueries true means unreferenced sessions
     * will be actively sought and expired. False means that they
     * will remain in hazelcast until some other mechanism removes them.
     */
    public void setUseQueries(boolean useQueries)
    {
        _useQueries = useQueries;
    }

    public boolean isUseQueries()
    {
        return _useQueries;
    }

    @Override
    public SessionData doLoad(String id)
        throws Exception
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Loading session {} from hazelcast", id);

            SessionData sd = sessionDataMap.get(getCacheKey(id));
            return sd;
        }
        catch (Exception e)
        {
            throw new UnreadableSessionDataException(id, _context, e);
        }
    }

    @Override
    public boolean delete(String id)
        throws Exception
    {
        if (sessionDataMap == null)
            return false;

        //use delete which does not deserialize the SessionData object being removed
        sessionDataMap.delete(getCacheKey(id));
        return true;
    }

    public IMap<String, SessionData> getSessionDataMap()
    {
        return sessionDataMap;
    }

    public void setSessionDataMap(IMap<String, SessionData> sessionDataMap)
    {
        this.sessionDataMap = sessionDataMap;
    }

    @Override
    public void initialize(SessionContext context)
        throws Exception
    {
        super.initialize(context);
        if (isUseQueries())
            sessionDataMap.addIndex(new IndexConfig(IndexType.SORTED, "expiry"));
    }

    @Override
    public void doStore(String id, SessionData data, long lastSaveTime)
        throws Exception
    {
        this.sessionDataMap.set(getCacheKey(id), data);
    }

    @Override
    public boolean isPassivating()
    {
        return true;
    }
    
    @Override
    public void doCleanOrphans(long timeLimit)
    {
        if (!isUseQueries())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Hazelcast useQueries=false, cannot clean orphaned sessions");
            return;
        }

        EntryObject eo = Predicates.newPredicateBuilder().getEntryObject();
        @SuppressWarnings("unchecked")
        Predicate<String, SessionData> predicate = eo.get("expiry").greaterThan(0)
            .and(eo.get("expiry").lessEqual(timeLimit));
        sessionDataMap.removeAll(predicate);
    }

    @Override
    public Set<String> doGetExpired(long time)
    {
        if (!isUseQueries())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Hazelcast useQueries=false, cannot search for expired sessions");
            return Collections.emptySet();
        }

        //Now find other sessions for our context in hazelcast that have expired
        final AtomicReference<Set<String>> reference = new AtomicReference<>();
        final AtomicReference<Exception> exception = new AtomicReference<>();

        _context.run(() ->
        {
            try
            {
                Set<String> ids = new HashSet<>();
                EntryObject eo = Predicates.newPredicateBuilder().getEntryObject();
                Predicate<String, SessionData> predicate = eo.get("expiry").greaterThan(0)
                    .and(eo.get("expiry").lessEqual(time))
                    .and(eo.get("contextPath").equal(_context.getCanonicalContextPath()));
                Collection<SessionData> results = sessionDataMap.values(predicate);
                if (results != null)
                {
                    for (SessionData sd : results)
                    {
                        ids.add(sd.getId());
                    }
                }
                reference.set(ids);
            }
            catch (Exception e)
            {
                exception.set(e);
            }
        });

        if (exception.get() != null)
        {
            LOG.warn("Error querying for expired sessions {}", exception.get());
            return Collections.emptySet();
        }

        if (reference.get() == null)
            return Collections.emptySet();
           
        return reference.get();
    }
    
    @Override
    public Set<String> doCheckExpired(Set<String> candidates, long time)
    {
        Set<String> expiredSessionIds = candidates.stream().filter(candidate ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Checking expiry for candidate {}", candidate);

            try
            {
                SessionData sd = load(candidate);

                //if the session no longer exists
                if (sd == null)
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Session {} does not exist in Hazelcast", candidate);
                    }
                    return true;
                }
                else
                {
                    if (_context.getWorkerName().equals(sd.getLastNode()))
                    {
                        //we are its manager, add it to the expired set if it is expired now
                        if ((sd.getExpiry() > 0) && sd.getExpiry() <= time)
                        {
                            if (LOG.isDebugEnabled())
                            {
                                LOG.debug("Session {} managed by {} is expired", candidate, _context.getWorkerName());
                            }
                            return true;
                        }
                    }
                }
            }
            catch (Exception e)
            {
                LOG.warn("Error checking if candidate {} is expired so expire it", candidate, e);
                return true;
            }
            return false;
        }).collect(Collectors.toSet());

        return expiredSessionIds;
    }

    @Override
    public boolean doExists(String id)
        throws Exception
    {
        //TODO find way to do query without pulling in whole session data
        SessionData sd = doLoad(id);
        if (sd == null)
            return false;

        if (sd.getExpiry() <= 0)
            return true; //never expires
        else
            return sd.getExpiry() > System.currentTimeMillis(); //not expired yet
    }

    public String getCacheKey(String id)
    {
        return _context.getCanonicalContextPath() + "_" + _context.getVhost() + "_" + id;
    }
}
