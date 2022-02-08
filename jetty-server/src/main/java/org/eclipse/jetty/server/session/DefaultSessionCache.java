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

package org.eclipse.jetty.server.session;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DefaultSessionCache
 *
 * A session store that keeps its sessions in memory within a concurrent map
 */
@ManagedObject
public class DefaultSessionCache extends AbstractSessionCache
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultSessionCache.class);

    /**
     * The cache of sessions in a concurrent map
     */
    private final ConcurrentMap<String, Session> _sessions;

    private final CounterStatistic _stats = new CounterStatistic();

    /**
     * @param manager The SessionHandler related to this SessionCache
     */
    public DefaultSessionCache(SessionHandler manager)
    {
        this(manager, new ConcurrentHashMap<>());
    }

    /**
     * @param manager The SessionHandler related to this SessionCache
     * @param sessions The session map implementation to use
     */
    public DefaultSessionCache(SessionHandler manager, ConcurrentMap<String, Session> sessions)
    {
        super(manager);
        _sessions = Objects.requireNonNull(sessions, "Session Map may not be null");
    }

    /**
     * @return the number of sessions in the cache
     */
    @ManagedAttribute(value = "current sessions in cache", readonly = true)
    public long getSessionsCurrent()
    {
        return _stats.getCurrent();
    }

    /**
     * @return the max number of sessions in the cache
     */
    @ManagedAttribute(value = "max sessions in cache", readonly = true)
    public long getSessionsMax()
    {
        return _stats.getMax();
    }

    /**
     * @return a running total of sessions in the cache
     */
    @ManagedAttribute(value = "total sessions in cache", readonly = true)
    public long getSessionsTotal()
    {
        return _stats.getTotal();
    }

    @ManagedOperation(value = "reset statistics", impact = "ACTION")
    public void resetStats()
    {
        _stats.reset();
    }

    @Override
    public Session doGet(String id)
    {
        if (id == null)
            return null;
        return _sessions.get(id);
    }

    @Override
    public Session doPutIfAbsent(String id, Session session)
    {
        Session s = _sessions.putIfAbsent(id, session);
        if (s == null)
            _stats.increment();
        return s;
    }

    @Override
    protected Session doComputeIfAbsent(String id, Function<String, Session> mappingFunction)
    {
        return _sessions.computeIfAbsent(id, k ->
        {
            Session s = mappingFunction.apply(k);
            if (s != null)
                _stats.increment();
            return s;
        });
    }

    @Override
    public Session doDelete(String id)
    {
        Session s = _sessions.remove(id);
        if (s != null)
            _stats.decrement();
        return s;
    }

    @Override
    public void shutdown()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Shutdown sessions, invalidating = {}", isInvalidateOnShutdown());

        // loop over all the sessions in memory (a few times if necessary to catch sessions that have been
        // added while we're running
        int loop = 100;

        while (!_sessions.isEmpty() && loop-- > 0)
        {
            for (Session session : _sessions.values())
            {
                if (isInvalidateOnShutdown())
                {
                    //not preserving sessions on exit
                    try
                    {
                        session.invalidate();
                    }
                    catch (Exception e)
                    {
                        LOG.trace("IGNORED", e);
                    }
                }
                else
                {
                    //write out the session and remove from the cache
                    if (_sessionDataStore.isPassivating())
                        session.willPassivate();
                    try
                    {
                        _sessionDataStore.store(session.getId(), session.getSessionData());
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Unable to store {}", session, e);
                    }
                    doDelete(session.getId()); //remove from memory
                    session.setResident(false);
                }
            }
        }
    }

    @Override
    public Session newSession(HttpServletRequest request, SessionData data)
    {
        return new Session(getSessionHandler(), request, data);
    }

    @Override
    public Session newSession(SessionData data)
    {
        return new Session(getSessionHandler(), data);
    }

    @Override
    public boolean doReplace(String id, Session oldValue, Session newValue)
    {
        return _sessions.replace(id, oldValue, newValue);
    }
}
