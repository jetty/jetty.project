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

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.statistic.CounterStatistic;

/**
 * DefaultSessionCache
 *
 * A session store that keeps its sessions in memory in a hashmap
 */
@ManagedObject
public class DefaultSessionCache extends AbstractSessionCache
{
    private static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

    /**
     * The cache of sessions in a hashmap
     */
    protected ConcurrentHashMap<String, Session> _sessions = new ConcurrentHashMap<>();

    private final CounterStatistic _stats = new CounterStatistic();

    /**
     * @param manager The SessionHandler related to this SessionCache
     */
    public DefaultSessionCache(SessionHandler manager)
    {
        super(manager);
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

        Session session = _sessions.get(id);

        return session;
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
                        LOG.ignore(e);
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
                        LOG.warn(e);
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
        Session s = new Session(getSessionHandler(), request, data);
        return s;
    }

    @Override
    public Session newSession(SessionData data)
    {
        Session s = new Session(getSessionHandler(), data);
        return s;
    }

    @Override
    public boolean doReplace(String id, Session oldValue, Session newValue)
    {
        boolean result = _sessions.replace(id, oldValue, newValue);
        return result;
    }
}
