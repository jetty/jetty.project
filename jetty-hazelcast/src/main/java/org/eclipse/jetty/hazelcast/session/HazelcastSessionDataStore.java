//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.hazelcast.session;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.hazelcast.core.IMap;
import com.hazelcast.query.EntryObject;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.PredicateBuilder;
import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.UnreadableSessionDataException;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Session data stored in Hazelcast
 */
@ManagedObject
public class HazelcastSessionDataStore
    extends AbstractSessionDataStore
    implements SessionDataStore
{

    private static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

    private IMap<String, SessionData> sessionDataMap;

    private boolean _scavengeZombies;

    public HazelcastSessionDataStore()
    {
    }

    /**
     * Control whether or not to execute queries to find
     * "zombie" sessions - ie sessions that are no longer
     * actively referenced by any jetty instance and should
     * be expired.
     *
     * If you use this feature, be aware that if your session
     * stores any attributes that use classes from within your
     * webapp, or from within jetty, you will need to make sure
     * those classes are available to all of your hazelcast
     * instances, whether embedded or remote.
     *
     * @param scavengeZombies true means unreferenced sessions
     * will be actively sought and expired. False means that they
     * will remain in hazelcast until some other mechanism removes them.
     */
    public void setScavengeZombieSessions(boolean scavengeZombies)
    {
        _scavengeZombies = scavengeZombies;
    }

    public boolean isScavengeZombies()
    {
        return _scavengeZombies;
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
        if (isScavengeZombies())
            sessionDataMap.addIndex("expiry", true);
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
    public Set<String> doGetExpired(Set<String> candidates)
    {
        long now = System.currentTimeMillis();

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
                        if ((sd.getExpiry() > 0) && sd.getExpiry() <= now)
                        {
                            if (LOG.isDebugEnabled())
                            {
                                LOG.debug("Session {} managed by {} is expired", candidate, _context.getWorkerName());
                            }
                            return true;
                        }
                    }
                    else
                    {
                        //if we are not the session's manager, only expire it iff:
                        // this is our first expiryCheck and the session expired a long time ago
                        //or
                        //the session expired at least one graceperiod ago
                        if (_lastExpiryCheckTime <= 0)
                        {
                            if ((sd.getExpiry() > 0) && sd.getExpiry() < (now - (1000L * (3 * _gracePeriodSec))))
                            {
                                return true;
                            }
                        }
                        else
                        {
                            if ((sd.getExpiry() > 0) && sd.getExpiry() < (now - (1000L * _gracePeriodSec)))
                            {
                                return true;
                            }
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

        if (isScavengeZombies())
        {
            //Now find other sessions in hazelcast that have expired
            final AtomicReference<Set<String>> reference = new AtomicReference<>();
            final AtomicReference<Exception> exception = new AtomicReference<>();

            _context.run(() ->
            {
                try
                {
                    Set<String> ids = new HashSet<>();
                    EntryObject eo = new PredicateBuilder().getEntryObject();
                    Predicate<?, ?> predicate = eo.get("expiry").greaterThan(0).and(eo.get("expiry").lessEqual(now));
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
                return expiredSessionIds;
            }

            if (reference.get() != null)
            {
                expiredSessionIds.addAll(reference.get());
            }
        }

        return expiredSessionIds;
    }

    @Override
    public boolean exists(String id)
        throws Exception
    {
        //TODO find way to do query without pulling in whole session data
        SessionData sd = load(id);
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
